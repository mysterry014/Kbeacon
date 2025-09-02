package com.kkmcn.sensordemo.cal;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.kkmcn.sensordemo.utils.RssiWindow;
import com.kkmcn.sensordemo.data.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 특정 MAC에 대한 캘리브레이션 세션
 * 
 * 기능:
 * - 3단계 측정 (1m/2m/3m)
 * - 광고 RSSI 기준 샘플 수집 (연결 중 RSSI 미사용)
 * - 이상치 제거 (윈도우 N=10, 중앙값 ±7dB)
 * - 선형회귀를 통한 txPowerAt1m, n 계산
 * - 품질평가 (R², RMSE, Residual)
 * 
 * 스레드 안전: UI 스레드 전제
 */
public class CalibrationSession {
    private static final String TAG = "CalibrationSession";
    
    // 수집 파라미터 (강화된 정책) - 타임·샘플 동시 조건
    private static final int REQUIRED_SAMPLES_PER_STAGE = 40; // 샘플 조건 (40개 필수)
    private static final int ABSOLUTE_MIN_SAMPLES = 15; // 최소 샘플 수 (강제 완료 조건)
    private static final int MAX_DURATION_MS_PER_STAGE = 20000; // 타임 조건 (20초 필수)
    private static final int TIMEOUT_GRACE_PERIOD_MS = 2000; // 타임아웃 유예기간 2초
    private static final int SAMPLE_RATE_MEASURE_MS = 3000; // 첫 3초간 샘플링 속도 측정
    private static final int ADAPTIVE_MIN_TARGET = 40; // 적응형 목표 하한 (40으로 상향)
    private static final int ADAPTIVE_MAX_TARGET = 60; // 적응형 목표 상한 (60으로 확장)
    private static final int WINDOW_SIZE = 10;
    private static final double OUTLIER_THRESHOLD_DB = 7.0;
    
    // 품질판정 임계값
    private static final double GOOD_R2_THRESHOLD = 0.85;
    private static final double GOOD_RMSE_THRESHOLD = 3.0;
    private static final double BORDERLINE_R2_THRESHOLD = 0.70;
    private static final double BORDERLINE_RMSE_THRESHOLD = 5.0;
    
    public enum CalibrationStage {
        STAGE_1M, STAGE_2M, STAGE_3M, COUNTDOWN_2M, COUNTDOWN_3M, COMPUTING, DONE, CANCELLED
    }
    
    public enum QualityRating {
        GOOD, BORDERLINE, BAD
    }
    
    public interface CalibrationListener {
        void onStageStarted(int stageIndex, double distanceMeters);
        void onStageProgress(int stageIndex, int sampleCount, int maxSamples, long remainingMs);
        void onStageCompleted(int stageIndex, double medianRssi);
        void onCalibrationFinished(CalibrationResult result);
        void onCalibrationError(String errorMessage);
        void onStageReadyToCollect(int stageIndex); // 카운트다운 완료 후 수집 준비 완료
        void resetBeaconFiltering(String mac); // 비콘 필터링 상태 리셋 (옵션)
    }
    
    public static class CalibrationResult {
        public final double txPowerAt1m;
        public final double pathLossExponent;
        public final double rSquared;
        public final double rmse;
        public final double maxResidual;
        public final QualityRating rating;
        public final long timestampMs;
        
        public CalibrationResult(double txPowerAt1m, double pathLossExponent, 
                               double rSquared, double rmse, double maxResidual, 
                               QualityRating rating) {
            this.txPowerAt1m = txPowerAt1m;
            this.pathLossExponent = pathLossExponent;
            this.rSquared = rSquared;
            this.rmse = rmse;
            this.maxResidual = maxResidual;
            this.rating = rating;
            this.timestampMs = System.currentTimeMillis();
        }
    }
    
    private final String mac;
    private final double[] distancesMeters; // {1.0, 2.0, 3.0}
    private final List<List<Integer>> stageRssiSamples; // 각 단계별 원시 샘플
    private final double[] stageMedianRssi; // 각 단계별 중앙값 결과
    
    private CalibrationStage currentStage;
    private long stageWaitStartMs;  // 카운트다운 시작 시각 (참고용)
    private long intakeStartMs;     // 실제 수집 시작 시각 (게이트 열린 시점)
    private CalibrationResult result;
    
    // 타임아웃 모드 및 샘플 추적 변수들
    private long lastSampleTimestamp = 0; // 마지막 샘플 수신 시각
    private int samplesInMeasurePeriod = 0; // 측정 구간 내 샘플 수
    private CalibrationListener listener;
    private boolean completedByTimeout = false; // 타임아웃으로 인한 완료 여부
    
    // [수집 게이트] 카운트다운 중에는 샘플 수집 차단
    private volatile boolean intakeEnabled = false;
    
    // 타임아웃 관리 (무한 대기 방지)
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable currentTimeoutTask = null;
    
    /**
     * 캘리브레이션 세션 생성
     * @param mac 대상 비콘 MAC 주소
     * @param distancesMeters 측정 거리 배열 (일반적으로 {1.0, 2.0, 3.0})
     */
    public CalibrationSession(String mac, double[] distancesMeters) {
        this.mac = mac;
        this.distancesMeters = distancesMeters.clone();
        this.stageRssiSamples = new ArrayList<>();
        this.stageMedianRssi = new double[distancesMeters.length];
        
        // 각 단계별 샘플 리스트 초기화
        for (int i = 0; i < distancesMeters.length; i++) {
            stageRssiSamples.add(new ArrayList<>());
        }
        
        // 첫 번째 단계 준비 (게이트 닫힘 상태)
        beginStageWaiting(0, CalibrationStage.STAGE_1M);
        
        Log.d(TAG, String.format("Calibration session started for MAC: %s, distances: [%.1f, %.1f, %.1f]", 
               mac, distancesMeters[0], distancesMeters[1], distancesMeters[2]));
    }
    
    /**
     * 캘리브레이션 리스너 설정
     * @param listener 콜백 리스너
     */
    public void setListener(CalibrationListener listener) {
        this.listener = listener;
    }
    
    /**
     * 새 RSSI 샘플 수집 (수집 게이트 열렸을 때만)
     * 
     * 주의: 광고 RSSI 기준으로 설계됨. 연결 중 RSSI는 사용하지 않음.
     * 
     * @param rssi 원시 RSSI 값 (dBm)
     */
    public synchronized void onRssiSample(int rssi) {
        int stageIndex = getCurrentStageIndex();
        Log.v(TAG, String.format("[INTAKE] onRssiSample: rssi=%d, intakeEnabled=%s, isCollecting=%s, stage=%s", 
                rssi, intakeEnabled, isCollecting(), currentStage));
        
        // [수집 게이트] 카운트다운 중이면 샘플 차단
        if (!intakeEnabled) {
            Log.v(TAG, "[INTAKE] Sample rejected - intake gate closed (countdown or waiting)");
            return;
        }
        
        if (!isCollecting()) {
            Log.v(TAG, "[INTAKE] Sample rejected - not in collecting stage");
            return;
        }
        
        if (stageIndex < 0) {
            Log.v(TAG, "[INTAKE] Sample rejected - invalid stage index");
            return;
        }
        
        stageRssiSamples.get(stageIndex).add(rssi);
        lastSampleTimestamp = System.currentTimeMillis();
        
        int sampleCount = stageRssiSamples.get(stageIndex).size();
        
        // ★ 강화된 로깅: 샘플 수집 상황
        Log.d(TAG, String.format("[SAMPLE] Stage %d sample accepted: %d dBm (total: %d/%d, target: %d)", 
               stageIndex + 1, rssi, sampleCount, REQUIRED_SAMPLES_PER_STAGE, REQUIRED_SAMPLES_PER_STAGE));
        
        // 진행 상황 콜백 (수집 시작 시각 기준)
        if (listener != null) {
            long elapsedSinceIntake = intakeStartMs > 0 ? 
                System.currentTimeMillis() - intakeStartMs : 0;
            long remaining = Math.max(0, MAX_DURATION_MS_PER_STAGE - elapsedSinceIntake);
            
            Log.v(TAG, String.format("[PROGRESS] Stage %d: sample accepted, count=%d/%d, elapsed=%dms, remaining=%dms", 
                    stageIndex + 1, sampleCount, REQUIRED_SAMPLES_PER_STAGE, elapsedSinceIntake, remaining));
            
            listener.onStageProgress(stageIndex, sampleCount, REQUIRED_SAMPLES_PER_STAGE, remaining);
        }
        
        // ★ 강화된 로깅: 완료 조건 체크 직전
        Log.v(TAG, String.format("[PRE-CHECK] Stage %d: About to check completion with count=%d, stageComplete=%s", 
               stageIndex + 1, sampleCount, stageComplete));
        
        // 완료 조건 체크 (적응형 목표 달성 또는 시간 상한)
        checkStageCompletion(stageIndex, sampleCount);
    }
    
    /**
     * 현재 단계의 샘플 수집이 완료되었는지 확인 (적응형 타겟 기준)
     * @return 완료 여부
     */
    public boolean isCurrentStageComplete() {
        if (!isCollecting()) {
            return false;
        }
        
        int stageIndex = getCurrentStageIndex();
        if (stageIndex < 0) {
            return false;
        }
        
        List<Integer> samples = stageRssiSamples.get(stageIndex);
        long elapsedSinceIntake = intakeStartMs > 0 ? 
            System.currentTimeMillis() - intakeStartMs : 0;
        
        // ★ 강화된 정책: 동시 조건 (40개 & 20초) 또는 강제 완료 조건
        boolean sampleRequirementMet = samples.size() >= REQUIRED_SAMPLES_PER_STAGE;
        boolean timeRequirementMet = elapsedSinceIntake >= MAX_DURATION_MS_PER_STAGE;
        boolean dualConditionMet = sampleRequirementMet && timeRequirementMet;
        
        // 강제 완료 조건: 최소 샘플 + 유예기간 초과
        boolean absoluteMinMet = samples.size() >= ABSOLUTE_MIN_SAMPLES;
        boolean graceTimeExpired = elapsedSinceIntake >= (MAX_DURATION_MS_PER_STAGE + TIMEOUT_GRACE_PERIOD_MS);
        boolean forceCompletionMet = absoluteMinMet && graceTimeExpired;
        
        Log.v(TAG, String.format("[COMPLETE-CHECK] Stage %d: samples=%d/%d, elapsed=%.1fs, dual=%s, force=%s", 
                stageIndex + 1, samples.size(), REQUIRED_SAMPLES_PER_STAGE, elapsedSinceIntake / 1000.0, 
                dualConditionMet, forceCompletionMet));
        
        return dualConditionMet || forceCompletionMet;
    }
    
    
    /**
     * 단계 대기 시작 (게이트 닫힘 + 상태 완전 초기화)
     * 카운트다운이 끝나면 enableIntakeForCurrentStage()를 호출해야 함
     * 
     * @param stageIndex 단계 인덱스 (0:1m, 1:2m, 2:3m)
     * @param stage 단계 enum
     */
    public synchronized void beginStageWaiting(int stageIndex, CalibrationStage stage) {
        Log.w(TAG, String.format("[STAGE-INIT] beginStageWaiting stage %d (%s) - FULL RESET", 
                stageIndex + 1, stage));
        
        // ★ 상태 완전 초기화 (탈동기화 방지)
        currentStage = stage;
        stageWaitStartMs = System.currentTimeMillis(); // 카운트다운 시작 시각
        intakeStartMs = 0; // 수집 시작 시각 리셋 (아직 시작 안 됨)
        intakeEnabled = false; // 게이트 닫기
        // [removed] 적응형 샘플링 제거로 extendedOnce 변수 삭제됨
        stageComplete = false; // 단계 완료 플래그 리셋 (중요!)
        // [removed] 적응형 샘플링 제거로 adaptiveTargetSamples 변수 삭제됨
        lastSampleTimestamp = 0; // 마지막 샘플 시각 리셋
        samplesInMeasurePeriod = 0; // 측정 구간 샘플 수 리셋
        
        // 이전 단계 타임아웃 취소
        cancelCurrentTimeout();
        
        // 해당 단계 버퍼 리셋
        resetStageBuffers(stageIndex);
        
        // ★ 비콘 필터링 상태 리셋 (각 단계 시작마다 깨끗한 상태로)
        if (listener != null) {
            try {
                listener.resetBeaconFiltering(mac);
                Log.d(TAG, String.format("[STAGE-INIT] Beacon filtering reset called for stage %d, mac=%s", stageIndex + 1, mac));
            } catch (Exception e) {
                Log.w(TAG, String.format("[STAGE-INIT] resetBeaconFiltering failed or not implemented: %s", e.getMessage()));
            }
        }
        
        Log.w(TAG, String.format("[STAGE-INIT] Stage %d initialized: stageComplete=%s, adaptiveTarget=%d, intakeEnabled=%s, bufferSize=%d", 
               stageIndex + 1, stageComplete, "fixed40+20s", intakeEnabled, 
               stageRssiSamples.get(stageIndex).size()));
        
        // ★ 단계 시작 콜백 (카운트다운 시작 신호)
        if (listener != null) {
            Log.w(TAG, String.format("[STAGE-CALLBACK] Calling onStageStarted(stageIndex=%d, distance=%.1fm)", 
                   stageIndex, distancesMeters[stageIndex]));
            listener.onStageStarted(stageIndex, distancesMeters[stageIndex]);
        } else {
            Log.e(TAG, String.format("[STAGE-CALLBACK] listener is null! Cannot call onStageStarted for stage %d", stageIndex + 1));
        }
    }
    
    /**
     * 카운트다운 완료 후 수집 게이트 열기
     * 버퍼가 깨끗한 상태에서 수집을 시작함을 보장
     */
    public synchronized void enableIntakeForCurrentStage() {
        int stageIndex = getCurrentStageIndex();
        
        // ★ 게이트 열기 직전 상태 확인 로깅
        int bufferSizeBefore = (stageIndex >= 0) ? stageRssiSamples.get(stageIndex).size() : -1;
        Log.w(TAG, String.format("[GATE-PRE] Stage %d before enableIntake: bufferSize=%d, stageComplete=%s, intakeEnabled=%s", 
                stageIndex + 1, bufferSizeBefore, stageComplete, intakeEnabled));
        
        // 수집 시작 시각 기록
        intakeStartMs = System.currentTimeMillis();
        
        // 샘플 추적 변수 초기화
        samplesInMeasurePeriod = 0; // 측정 구간 카운터 리셋
        lastSampleTimestamp = 0; // 샘플 시각 리셋
        completedByTimeout = false; // 타임아웃 플래그 리셋
        
        // ★ 게이트 열기 (이 시점부터 샘플 수집 시작)
        intakeEnabled = true;
        
        Log.w(TAG, String.format("[GATE-ENABLED] Stage %d: intake=true, collection started at %d, adaptive reset, stageComplete=%s", 
                stageIndex + 1, intakeStartMs, stageComplete));
        
        // 타임아웃 태스크 설정 (무한 대기 방지)
        scheduleStageTimeout(stageIndex);
        
        // 수집 준비 완료 콜백
        if (listener != null) {
            listener.onStageReadyToCollect(stageIndex);
            Log.d(TAG, String.format("[GATE-CALLBACK] Stage %d: onStageReadyToCollect called", stageIndex + 1));
        } else {
            Log.e(TAG, String.format("[GATE-CALLBACK] Stage %d: listener is null!", stageIndex + 1));
        }
    }
    
    /**
     * 단계별 버퍼 초기화
     * @param stageIndex 단계 인덱스
     */
    private void resetStageBuffers(int stageIndex) {
        if (stageIndex >= 0 && stageIndex < stageRssiSamples.size()) {
            int previousCount = stageRssiSamples.get(stageIndex).size();
            stageRssiSamples.get(stageIndex).clear();
            Log.d(TAG, String.format("[BUFFER] Stage %d buffers reset (was %d samples, now %d)", 
                    stageIndex + 1, previousCount, stageRssiSamples.get(stageIndex).size()));
            
            // 연장 플래그도 리셋
            // [removed] 적응형 샘플링 제거로 extendedOnce 변수 삭제됨
        }
    }
    
    /**
     * 캘리브레이션 완료 처리
     */
    private void finishCalibration() {
        currentStage = CalibrationStage.COMPUTING;
        Log.d(TAG, "Starting regression computation");
        
        try {
            computeLinearRegression();
            currentStage = CalibrationStage.DONE;
            
            Log.i(TAG, String.format("Calibration finished: result=%s", 
                   result != null ? result.rating : "null"));
            
            // 완료 콜백 (반드시 호출)
            if (listener != null) {
                if (result != null) {
                    listener.onCalibrationFinished(result);
                } else {
                    listener.onCalibrationError("Regression computation failed - no result generated");
                }
            }
        } catch (Exception e) {
            currentStage = CalibrationStage.DONE;
            Log.e(TAG, "Calibration computation failed: " + e.getMessage(), e);
            
            // 오류 콜백
            if (listener != null) {
                listener.onCalibrationError("Computation failed: " + e.getMessage());
            }
        }
    }
    
    
    /**
     * 샘플 수집 중인지 확인
     * @return 수집 중 여부
     */
    public boolean isCollecting() {
        return currentStage == CalibrationStage.STAGE_1M || 
               currentStage == CalibrationStage.STAGE_2M || 
               currentStage == CalibrationStage.STAGE_3M;
    }
    
    /**
     * 현재 단계 반환
     * @return 현재 단계
     */
    public CalibrationStage getStage() {
        return currentStage;
    }
    
    /**
     * 현재 단계의 샘플 수 반환
     * @return 샘플 수
     */
    public int getCurrentStageSampleCount() {
        int stageIndex = getCurrentStageIndex();
        if (stageIndex < 0) {
            return 0;
        }
        return stageRssiSamples.get(stageIndex).size();
    }
    
    /**
     * 캘리브레이션 결과 반환
     * @return 결과 (완료되지 않은 경우 null)
     */
    public CalibrationResult getResult() {
        return result;
    }
    
    /**
     * 대상 MAC 주소 반환
     * @return MAC 주소
     */
    public String getMac() {
        return mac;
    }
    
    // Private helper methods
    
    private int getCurrentStageIndex() {
        switch (currentStage) {
            case STAGE_1M: return 0;
            case STAGE_2M: return 1;
            case STAGE_3M: return 2;
            default: return -1;
        }
    }
    
    /**
     * 이상치 제거 후 중앙값 계산
     * 
     * 알고리즘:
     * 1. 윈도우 크기(N=10) 유지
     * 2. 중앙값 기준 ±7dB 범위 밖 샘플 제거
     * 3. 유효 샘플들의 중앙값 반환
     * 
     * @param samples 원시 RSSI 샘플 리스트
     * @return 필터링된 중앙값
     */
    private double calculateFilteredMedian(List<Integer> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        
        // RssiWindow 로직 재사용
        RssiWindow window = new RssiWindow(WINDOW_SIZE, OUTLIER_THRESHOLD_DB);
        
        for (Integer rssi : samples) {
            window.addSample(rssi);
        }
        
        List<Integer> validSamples = window.getFilteredSamples();
        if (validSamples.isEmpty()) {
            // 모든 샘플이 이상치인 경우, 원시 샘플의 중앙값 사용
            List<Integer> sortedSamples = new ArrayList<>(samples);
            Collections.sort(sortedSamples);
            int midIndex = sortedSamples.size() / 2;
            return sortedSamples.get(midIndex);
        }
        
        Collections.sort(validSamples);
        int midIndex = validSamples.size() / 2;
        double median = validSamples.get(midIndex);
        
        Log.v(TAG, String.format("Filtered median: %d raw samples → %d valid samples → %.1f dBm", 
               samples.size(), validSamples.size(), median));
        
        return median;
    }
    
    /**
     * 선형회귀 계산 및 품질평가
     * 
     * 모델: RSSI = txPowerAt1m - 10*n*log10(distance)
     * 회귀 입력: x_i = log10(d_i), y_i = medianRSSI(d_i)
     * 결과: slope m, intercept b → n = -m/10, txPowerAt1m = b
     */
    private void computeLinearRegression() {
        Log.d(TAG, "Computing linear regression...");
        
        // 회귀 데이터 준비
        double[] x = new double[distancesMeters.length]; // log10(distance)
        double[] y = new double[distancesMeters.length]; // median RSSI
        
        for (int i = 0; i < distancesMeters.length; i++) {
            x[i] = Math.log10(distancesMeters[i]);
            y[i] = stageMedianRssi[i];
            
            Log.d(TAG, String.format("Regression data point %d: x=%.3f (%.1fm), y=%.1f dBm", 
                   i, x[i], distancesMeters[i], y[i]));
        }
        
        // 선형회귀 계산: y = mx + b
        LinearRegressionResult regression = performLinearRegression(x, y);
        
        // 파라미터 변환 (부호 수정)
        double pathLossExponent = -regression.slope / 10.0; // n = -slope/10 (부호 처리 주의)
        double txPowerAt1m = regression.intercept; // tx1m = intercept
        
        // [NaN/Inf 방어] 회귀 결과 유효성 검사
        if (Double.isNaN(regression.slope) || Double.isInfinite(regression.slope) ||
            Double.isNaN(regression.intercept) || Double.isInfinite(regression.intercept)) {
            Log.e(TAG, String.format("Degenerate regression: slope=%.3f, intercept=%.3f", 
                   regression.slope, regression.intercept));
            result = createBadResult("Degenerate regression - slope or intercept invalid");
            return;
        }
        
        // 물리적 유효성 검사 (경로손실지수 범위)
        if (!(pathLossExponent > 0.8 && pathLossExponent < 6.0)) {
            Log.e(TAG, String.format("Invalid path loss exponent: n=%.3f (should be 0.8 < n < 6.0)", pathLossExponent));
            result = createBadResult("Invalid path loss exponent - outside physical range");
            return;
        }
        
        // 품질평가
        double rSquared = calculateRSquared(x, y, regression.slope, regression.intercept);
        double rmse = calculateRMSE(x, y, regression.slope, regression.intercept);
        double maxResidual = calculateMaxResidual(x, y, regression.slope, regression.intercept);
        
        // [NaN/Inf 방어] 품질 지표 유효성 검사
        if (Double.isNaN(rSquared) || Double.isNaN(rmse) || 
            Double.isInfinite(rSquared) || Double.isInfinite(rmse)) {
            Log.e(TAG, String.format("Quality metrics invalid: R²=%.3f, RMSE=%.3f", rSquared, rmse));
            result = createBadResult("Quality computation failed - metrics invalid");
            return;
        }
        
        // 품질판정
        QualityRating rating = determineQualityRating(rSquared, rmse);
        
        // 결과 저장 (소수점 둘째자리까지)
        result = new CalibrationResult(
            Math.round(txPowerAt1m * 100.0) / 100.0,
            Math.round(pathLossExponent * 100.0) / 100.0,
            Math.round(rSquared * 100.0) / 100.0,
            Math.round(rmse * 100.0) / 100.0,
            Math.round(maxResidual * 100.0) / 100.0,
            rating
        );
        
        Log.i(TAG, String.format("Calibration complete: txPower1m=%.2f, n=%.2f, R²=%.2f, RMSE=%.2f, rating=%s", 
               result.txPowerAt1m, result.pathLossExponent, result.rSquared, result.rmse, result.rating));
    }
    
    
    // stageComplete 플래그 추가 (중복 방지)
    private volatile boolean stageComplete = false;
    
    /**
     * 단계 완료 확인 (강화된 정책 - 타임·샘플 동시 조건)
     * @param stageIndex 단계 인덱스 (0,1,2)
     * @param sampleCount 현재 샘플 수
     */
    private void checkStageCompletion(int stageIndex, int sampleCount) {
        long elapsedMs = System.currentTimeMillis() - intakeStartMs;
        
        // ★ 강화된 정책: 동시 조건 (40개 & 20초) 또는 최소 조건 (15개 + 타임아웃)
        boolean sampleRequirementMet = sampleCount >= REQUIRED_SAMPLES_PER_STAGE;
        boolean timeRequirementMet = elapsedMs >= MAX_DURATION_MS_PER_STAGE;
        boolean dualConditionMet = sampleRequirementMet && timeRequirementMet; // 동시 만족
        
        // 강제 완료 조건: 최소 샘플 + 유예기간 초과
        boolean absoluteMinMet = sampleCount >= ABSOLUTE_MIN_SAMPLES;
        boolean graceTimeExpired = elapsedMs >= (MAX_DURATION_MS_PER_STAGE + TIMEOUT_GRACE_PERIOD_MS);
        boolean forceCompletionMet = absoluteMinMet && graceTimeExpired;
        
        // 타임아웃 플래그 설정
        if (timeRequirementMet && !sampleRequirementMet) {
            completedByTimeout = true;
        } else if (forceCompletionMet) {
            completedByTimeout = true;
        } else if (dualConditionMet) {
            completedByTimeout = false; // 정상 완료
        }
        
        // ★ 강화된 로깅: 완료 조건 체크 전 상태
        Log.d(TAG, String.format("[COMPLETION-GATE] Stage %d: samples=%d/%d, elapsed=%.1fs, sampleMet=%s, timeMet=%s, dualMet=%s, forceMet=%s, timeout=%s, complete=%s", 
            stageIndex + 1, sampleCount, REQUIRED_SAMPLES_PER_STAGE, elapsedMs / 1000.0, 
            sampleRequirementMet, timeRequirementMet, dualConditionMet, forceCompletionMet, completedByTimeout, stageComplete));
        
        // 완료 조건 체크 및 중복 방지
        if ((dualConditionMet || forceCompletionMet) && !stageComplete) {
            // ★ 강화된 로깅: 게이트 통과 직전
            String reason = dualConditionMet ? "dual condition met" : "forced completion (timeout + min samples)";
            Log.w(TAG, String.format("[GATE-FIRED] Stage %d: TRIGGERING COMPLETION → %s", stageIndex + 1, reason));
            
            stageComplete = true; // 중복 방지 플래그 설정
            
            // 현재 타임아웃 취소 (정상 완료)
            cancelCurrentTimeout();
            
            Log.i(TAG, String.format("[STAGE-%dm] Completing stage: %s (%d samples in %.1f sec, timeout=%s)", 
                stageIndex + 1, reason, sampleCount, elapsedMs / 1000.0, completedByTimeout));
            
            // 현재 단계의 중앙값 계산
            List<Integer> samples = stageRssiSamples.get(stageIndex);
            double medianRssi = calculateFilteredMedian(samples);
            stageMedianRssi[stageIndex] = medianRssi;
            
            Log.d(TAG, String.format("Stage %d complete: %d samples → median RSSI: %.1f dBm (timeout=%s)", 
                   stageIndex + 1, samples.size(), medianRssi, completedByTimeout));
            
            // ★ 강화된 로깅: 콜백 호출 직전
            Log.w(TAG, String.format("[CALLBACK] Stage %d: CALLING onStageCompleted(stageIndex=%d, medianRssi=%.1f)", 
                stageIndex + 1, stageIndex, medianRssi));
            
            // 단계 완료 콜백 (반드시 호출)
            if (listener != null) {
                listener.onStageCompleted(stageIndex, medianRssi);
                Log.w(TAG, String.format("[CALLBACK] Stage %d: onStageCompleted CALLED SUCCESSFULLY", stageIndex + 1));
            } else {
                Log.e(TAG, String.format("[CALLBACK] Stage %d: onStageCompleted NOT CALLED - listener is null!", stageIndex + 1));
            }
            
            // 다음 단계로 진행
            if (stageIndex == 0) {
                Log.i(TAG, "[TRANSITION] Stage 1→2: Moving to COUNTDOWN_2M");
                beginStageWaiting(1, CalibrationStage.STAGE_2M);
            } else if (stageIndex == 1) {
                Log.i(TAG, "[TRANSITION] Stage 2→3: Moving to COUNTDOWN_3M");
                beginStageWaiting(2, CalibrationStage.STAGE_3M);
            } else if (stageIndex == 2) {
                Log.i(TAG, "[TRANSITION] Stage 3→COMPUTING: Starting final computation");
                finishCalibration();
            }
        } else if ((dualConditionMet || forceCompletionMet) && stageComplete) {
            // ★ 강화된 로깅: 중복 완료 시도 감지
            Log.w(TAG, String.format("[GATE-BLOCKED] Stage %d: COMPLETION BLOCKED - already stageComplete=true (samples=%d/%d)", 
                stageIndex + 1, sampleCount, REQUIRED_SAMPLES_PER_STAGE));
        } else {
            // 타임아웃 상황 표시 (유예기간 진입 시)
            String timeoutWarning = "";
            if (timeRequirementMet && !sampleRequirementMet && !graceTimeExpired) {
                long graceRemaining = (MAX_DURATION_MS_PER_STAGE + TIMEOUT_GRACE_PERIOD_MS) - elapsedMs;
                timeoutWarning = String.format(" [TIMEOUT - grace: %.1fs]", graceRemaining / 1000.0);
            }
            
            Log.v(TAG, String.format("[STAGE-%dm] Continue collecting: %d/%d samples, %.1f/%.1f sec%s", 
                stageIndex + 1, sampleCount, REQUIRED_SAMPLES_PER_STAGE, 
                elapsedMs / 1000.0, MAX_DURATION_MS_PER_STAGE / 1000.0, timeoutWarning));
        }
    }
    
    /**
     * 단계 타임아웃 스케줄링 (무한 대기 방지)
     * @param stageIndex 단계 인덱스
     */
    private void scheduleStageTimeout(int stageIndex) {
        // 기존 타임아웃 취소
        cancelCurrentTimeout();
        
        // 새 타임아웃 태스크 생성 - 강제 완료 보장
        currentTimeoutTask = () -> {
            Log.w(TAG, String.format("[TIMEOUT] Stage %d forced completion due to max duration timeout", stageIndex + 1));
            
            synchronized (CalibrationSession.this) {
                if (!stageComplete && isCollecting() && getCurrentStageIndex() == stageIndex) {
                    stageComplete = true; // 강제 완료 플래그
                    
                    // 현재 단계의 중앙값 계산
                    List<Integer> samples = stageRssiSamples.get(stageIndex);
                    double medianRssi = calculateFilteredMedian(samples);
                    stageMedianRssi[stageIndex] = medianRssi;
                    
                    Log.w(TAG, String.format("Stage %d timeout complete: %d samples → median RSSI: %.1f dBm", 
                           stageIndex + 1, samples.size(), medianRssi));
                    
                    // 다음 단계로 진행 (타임아웃으로 인한 강제 완료)
                    if (stageIndex == 0) {
                        Log.i(TAG, "[TIMEOUT-TRANSITION] Stage 1→2: Moving to COUNTDOWN_2M");
                        beginStageWaiting(1, CalibrationStage.STAGE_2M);
                    } else if (stageIndex == 1) {
                        Log.i(TAG, "[TIMEOUT-TRANSITION] Stage 2→3: Moving to COUNTDOWN_3M");
                        beginStageWaiting(2, CalibrationStage.STAGE_3M);
                    } else if (stageIndex == 2) {
                        Log.i(TAG, "[TIMEOUT-TRANSITION] Stage 3→COMPUTING: Starting final computation");
                        finishCalibration();
                    }
                    
                    // 추가 완료 콜백 (UI용)
                    if (listener != null) {
                        listener.onStageCompleted(stageIndex, medianRssi);
                    }
                    
                    // 다음 단계로 진행
                    if (stageIndex == 0) {
                        Log.i(TAG, "[TIMEOUT-TRANSITION] Stage 1→2: Moving to COUNTDOWN_2M");
                        beginStageWaiting(1, CalibrationStage.STAGE_2M);
                    } else if (stageIndex == 1) {
                        Log.i(TAG, "[TIMEOUT-TRANSITION] Stage 2→3: Moving to COUNTDOWN_3M");
                        beginStageWaiting(2, CalibrationStage.STAGE_3M);
                    } else if (stageIndex == 2) {
                        Log.i(TAG, "[TIMEOUT-TRANSITION] Stage 3→COMPUTING: Starting final computation");
                        finishCalibration();
                    }
                }
            }
        };
        
        // 타임아웃 스케줄링 (15초 + 5초 연장 = 최대 20초)
        int timeoutMs = MAX_DURATION_MS_PER_STAGE + 5000; // 5초 유예시간
        timeoutHandler.postDelayed(currentTimeoutTask, timeoutMs);
        
        Log.d(TAG, String.format("[TIMEOUT] Scheduled timeout for stage %d in %dms", stageIndex + 1, timeoutMs));
    }
    
    /**
     * 현재 타임아웃 태스크 취소
     */
    private void cancelCurrentTimeout() {
        if (currentTimeoutTask != null) {
            timeoutHandler.removeCallbacks(currentTimeoutTask);
            currentTimeoutTask = null;
            Log.v(TAG, "[TIMEOUT] Cancelled current timeout task");
        }
    }
    
    /**
     * 캘리브레이션 취소 시 타임아웃 정리
     */
    public void cancel() {
        cancelCurrentTimeout();
        currentStage = CalibrationStage.CANCELLED;
        Log.d(TAG, "Calibration session cancelled for MAC: " + mac);
    }
    
    
    private static class LinearRegressionResult {
        final double slope;
        final double intercept;
        
        LinearRegressionResult(double slope, double intercept) {
            this.slope = slope;
            this.intercept = intercept;
        }
    }
    
    /**
     * 최소제곱법 선형회귀
     * @param x 독립변수 배열
     * @param y 종속변수 배열
     * @return 기울기와 절편
     */
    private LinearRegressionResult performLinearRegression(double[] x, double[] y) {
        int n = x.length;
        
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumXX += x[i] * x[i];
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;
        
        return new LinearRegressionResult(slope, intercept);
    }
    
    /**
     * R² (결정계수) 계산
     */
    private double calculateRSquared(double[] x, double[] y, double slope, double intercept) {
        double meanY = 0;
        for (double value : y) {
            meanY += value;
        }
        meanY /= y.length;
        
        double ssRes = 0; // 잔차 제곱합
        double ssTot = 0; // 총 제곱합
        
        for (int i = 0; i < y.length; i++) {
            double predicted = slope * x[i] + intercept;
            ssRes += Math.pow(y[i] - predicted, 2);
            ssTot += Math.pow(y[i] - meanY, 2);
        }
        
        return 1.0 - (ssRes / ssTot);
    }
    
    /**
     * RMSE (평균제곱근오차) 계산
     */
    private double calculateRMSE(double[] x, double[] y, double slope, double intercept) {
        double sumSquaredError = 0;
        
        for (int i = 0; i < y.length; i++) {
            double predicted = slope * x[i] + intercept;
            sumSquaredError += Math.pow(y[i] - predicted, 2);
        }
        
        return Math.sqrt(sumSquaredError / y.length);
    }
    
    /**
     * 최대 잔차 계산
     */
    private double calculateMaxResidual(double[] x, double[] y, double slope, double intercept) {
        double maxResidual = 0;
        
        for (int i = 0; i < y.length; i++) {
            double predicted = slope * x[i] + intercept;
            double residual = Math.abs(y[i] - predicted);
            maxResidual = Math.max(maxResidual, residual);
        }
        
        return maxResidual;
    }
    
    /**
     * BAD 품질의 결과 생성 (오류 상황용)
     * @param reason 오류 원인
     * @return BAD 품질의 결과
     */
    private CalibrationResult createBadResult(String reason) {
        Log.w(TAG, "Creating BAD result: " + reason);
        return new CalibrationResult(
            Prefs.getDefaultTxPowerAt1m(), // 기본값 사용
            Prefs.getDefaultPathLossExponent(),
            0.0, // R² = 0
            999.0, // RMSE = 999 (매우 나쁨)
            999.0, // maxResidual = 999
            QualityRating.BAD
        );
    }
    
    /**
     * 품질판정
     * 
     * GOOD: R² ≥ 0.85 && RMSE ≤ 3.0
     * BORDERLINE: R² ≥ 0.70 && RMSE ≤ 5.0
     * BAD: 그 외
     */
    private QualityRating determineQualityRating(double rSquared, double rmse) {
        if (rSquared >= GOOD_R2_THRESHOLD && rmse <= GOOD_RMSE_THRESHOLD) {
            return QualityRating.GOOD;
        } else if (rSquared >= BORDERLINE_R2_THRESHOLD && rmse <= BORDERLINE_RMSE_THRESHOLD) {
            return QualityRating.BORDERLINE;
        } else {
            return QualityRating.BAD;
        }
    }
}