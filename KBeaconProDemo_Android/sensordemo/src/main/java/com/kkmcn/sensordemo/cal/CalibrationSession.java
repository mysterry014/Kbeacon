package com.kkmcn.sensordemo.cal;

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
    
    // 수집 파라미터
    private static final int MIN_SAMPLES_PER_STAGE = 25;
    private static final int MAX_DURATION_MS_PER_STAGE = 20000; // 20초
    private static final int SOFT_EXTEND_MS = 10000; // 1차 연장 10초
    private static final int WINDOW_SIZE = 10;
    private static final double OUTLIER_THRESHOLD_DB = 7.0;
    
    // 품질판정 임계값
    private static final double GOOD_R2_THRESHOLD = 0.85;
    private static final double GOOD_RMSE_THRESHOLD = 3.0;
    private static final double BORDERLINE_R2_THRESHOLD = 0.70;
    private static final double BORDERLINE_RMSE_THRESHOLD = 5.0;
    
    public enum CalibrationStage {
        STAGE_1M, STAGE_2M, STAGE_3M, COMPUTING, DONE, CANCELLED
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
    private CalibrationListener listener;
    
    // [수집 게이트] 카운트다운 중에는 샘플 수집 차단
    private volatile boolean intakeEnabled = false;
    private boolean extendedOnce = false; // 1차 연장 여부
    
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
        
        int sampleCount = stageRssiSamples.get(stageIndex).size();
        Log.d(TAG, String.format("[SAMPLE] Stage %d sample accepted: %d dBm (total: %d)", 
               stageIndex + 1, rssi, sampleCount));
        
        // 진행 상황 콜백 (수집 시작 시각 기준)
        if (listener != null) {
            long elapsedSinceIntake = intakeStartMs > 0 ? 
                System.currentTimeMillis() - intakeStartMs : 0;
            long remaining = Math.max(0, MAX_DURATION_MS_PER_STAGE - elapsedSinceIntake);
            
            Log.v(TAG, String.format("[PROGRESS] Stage %d: sample accepted, count=%d, elapsedSinceIntake=%dms, remaining=%dms", 
                    stageIndex + 1, sampleCount, elapsedSinceIntake, remaining));
            
            listener.onStageProgress(stageIndex, sampleCount, MIN_SAMPLES_PER_STAGE, remaining);
        }
    }
    
    /**
     * 현재 단계의 샘플 수집이 완료되었는지 확인
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
        
        // ★ 수집 시작 시각 기준으로 타임아웃 체크 (카운트다운 제외)
        long elapsedSinceIntake = intakeStartMs > 0 ? 
            System.currentTimeMillis() - intakeStartMs : 0;
        
        boolean hasEnoughSamples = samples.size() >= MIN_SAMPLES_PER_STAGE;
        boolean isBaseTimeout = intakeStartMs > 0 && elapsedSinceIntake >= MAX_DURATION_MS_PER_STAGE;
        boolean isExtendedTimeout = extendedOnce && intakeStartMs > 0 && elapsedSinceIntake >= SOFT_EXTEND_MS;
        
        Log.v(TAG, String.format("[TIMEOUT] Stage %d: samples=%d/%d, elapsedSinceIntake=%dms, baseTimeout=%s, extendedTimeout=%s, extended=%s", 
                stageIndex + 1, samples.size(), MIN_SAMPLES_PER_STAGE, elapsedSinceIntake, 
                isBaseTimeout, isExtendedTimeout, extendedOnce));
        
        // 최소 샘플 수 달성 또는 완전 타임아웃
        if (hasEnoughSamples) {
            return true;
        }
        
        // 기본 20초 타임아웃 시 연장 처리
        if (isBaseTimeout && !extendedOnce && samples.size() > 0) {
            Log.d(TAG, String.format("[EXTEND] Stage %d: insufficient samples (%d/%d), extending 10s more", 
                    stageIndex + 1, samples.size(), MIN_SAMPLES_PER_STAGE));
            
            // 1차 연장 시작
            intakeStartMs = System.currentTimeMillis(); // 연장 시작 지점으로 리셋
            extendedOnce = true;
            return false; // 아직 완료 안 됨
        }
        
        // 연장도 끝났거나 샘플이 아예 없으면 종료
        return isExtendedTimeout || (isBaseTimeout && samples.size() == 0);
    }
    
    /**
     * 다음 단계로 진행하거나 회귀 계산 수행
     * @return 성공 여부
     */
    public boolean nextStageOrCompute() {
        if (!isCurrentStageComplete()) {
            Log.w(TAG, "Current stage not complete, cannot proceed");
            return false;
        }
        
        int stageIndex = getCurrentStageIndex();
        if (stageIndex < 0) {
            return false;
        }
        
        // 현재 단계의 중앙값 계산
        List<Integer> samples = stageRssiSamples.get(stageIndex);
        double medianRssi = calculateFilteredMedian(samples);
        stageMedianRssi[stageIndex] = medianRssi;
        
        Log.d(TAG, String.format("Stage %d complete: %d samples → median RSSI: %.1f dBm", 
               stageIndex + 1, samples.size(), medianRssi));
        
        // 단계 완료 콜백
        if (listener != null) {
            listener.onStageCompleted(stageIndex, medianRssi);
        }
        
        // 다음 단계로 진행 (게이트 닫힐 상태로)
        switch (currentStage) {
            case STAGE_1M:
                beginStageWaiting(1, CalibrationStage.STAGE_2M);
                return true;
                
            case STAGE_2M:
                beginStageWaiting(2, CalibrationStage.STAGE_3M);
                return true;
                
            case STAGE_3M:
                // 모든 단계 완료 → 회귀 계산
                finishCalibration();
                return true;
                
            default:
                return false;
        }
    }
    
    /**
     * 단계 대기 시작 (게이트 닫힘 + 버퍼 리셋)
     * 카운트다운이 끝나면 enableIntakeForCurrentStage()를 호출해야 함
     * 
     * @param stageIndex 단계 인덱스 (0:1m, 1:2m, 2:3m)
     * @param stage 단계 enum
     */
    public synchronized void beginStageWaiting(int stageIndex, CalibrationStage stage) {
        Log.d(TAG, String.format("[GATE] beginStageWaiting stage %d (%s) - intake=false, buffers reset", 
                stageIndex + 1, stage));
        
        currentStage = stage;
        stageWaitStartMs = System.currentTimeMillis(); // 카운트다운 시작 시각
        intakeEnabled = false; // 게이트 닫기
        extendedOnce = false;  // 연장 플래그 리셋
        
        // 해당 단계 버퍼 리셋
        resetStageBuffers(stageIndex);
        
        // 단계 시작 콜백 (카운트다운 시작 신호)
        if (listener != null) {
            listener.onStageStarted(stageIndex, distancesMeters[stageIndex]);
        }
    }
    
    /**
     * 카운트다운 완료 후 수집 게이트 열기
     */
    public synchronized void enableIntakeForCurrentStage() {
        int stageIndex = getCurrentStageIndex();
        intakeStartMs = System.currentTimeMillis(); // ★ 실제 수집 시작 시각 기록
        
        Log.d(TAG, String.format("[GATE] enableIntake stage %d - intake=true, collection started at %d", 
                stageIndex + 1, intakeStartMs));
        
        intakeEnabled = true; // 게이트 열기
        
        // 수집 준비 완료 콜백
        if (listener != null) {
            listener.onStageReadyToCollect(stageIndex);
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
        extendedOnce = false;
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
     * 캘리브레이션 취소
     */
    public void cancel() {
        currentStage = CalibrationStage.CANCELLED;
        Log.d(TAG, "Calibration session cancelled for MAC: " + mac);
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
        
        // 파라미터 변환
        double pathLossExponent = -regression.slope / 10.0; // n = -m/10
        double txPowerAt1m = regression.intercept; // tx1m = b
        
        // [NaN/Inf 방어] 회귀 결과 유효성 검사
        if (Double.isNaN(regression.slope) || Double.isInfinite(regression.slope) ||
            Double.isNaN(regression.intercept) || Double.isInfinite(regression.intercept)) {
            Log.e(TAG, String.format("Degenerate regression: slope=%.3f, intercept=%.3f", 
                   regression.slope, regression.intercept));
            result = createBadResult("Degenerate regression - slope or intercept invalid");
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