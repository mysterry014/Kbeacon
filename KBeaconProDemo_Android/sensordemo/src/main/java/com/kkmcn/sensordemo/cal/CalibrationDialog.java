package com.kkmcn.sensordemo.cal;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.kkmcn.sensordemo.R;
import com.kkmcn.sensordemo.cal.CalibrationSession.CalibrationStage;
import com.kkmcn.sensordemo.cal.CalibrationSession.QualityRating;

/**
 * 캘리브레이션 다이얼로그 컨트롤러
 * 
 * 기능:
 * - 3단계 측정 UX (1m/2m/3m)
 * - 실시간 샘플 카운트/타이머 표시
 * - 회귀 결과 및 품질평가 표시
 * - 저장/재측정/취소 제어
 * 
 * 가드: 진행 중 자동/수동 알람 차단
 */
public class CalibrationDialog {
    private static final String TAG = "CalibrationDialog";
    
    // 카운트다운 설정
    private static final int COUNTDOWN_SECONDS = 3;
    private static final int UI_UPDATE_INTERVAL_MS = 500;
    
    public interface CalibrationCallback {
        void onCalibrationStarted();
        void onCalibrationFinished(boolean saved);
        void onSampleNeeded(CalibrationSession session);
        String getBeaconDisplayName(String mac);
        void saveCalibrationResult(String mac, CalibrationSession.CalibrationResult result);
        CalibrationSession.CalibrationResult loadCalibrationResult(String mac);
    }
    
    /**
     * 캘리브레이션 다이얼로그 표시
     * @param context 컨텍스트
     * @param mac 대상 비콘 MAC 주소
     * @param callback 콜백 인터페이스
     */
    public static void show(Context context, String mac, CalibrationCallback callback) {
        new CalibrationDialog(context, mac, callback).show();
    }
    
    private final Context context;
    private final String mac;
    private final CalibrationCallback callback;
    
    private AlertDialog dialog;
    private CalibrationSession session;
    private Handler uiHandler;
    private Runnable uiUpdateRunnable;
    
    // UI 컴포넌트들
    private TextView tvTitle;
    private TextView tvInstructions;
    private LinearLayout layoutStages;
    private TextView[] stageStatusTexts;
    private Button btnAction;
    private Button btnCancel;
    private LinearLayout layoutResult;
    private TextView tvResult;
    
    private boolean isCountingDown = false;
    private int countdownRemaining = 0;
    
    private CalibrationDialog(Context context, String mac, CalibrationCallback callback) {
        this.context = context;
        this.mac = mac;
        this.callback = callback;
        this.uiHandler = new Handler();
    }
    
    private void show() {
        Log.d(TAG, "Showing calibration dialog for MAC: " + mac);
        
        // 기존 결과 확인
        CalibrationSession.CalibrationResult existingResult = callback.loadCalibrationResult(mac);
        
        // 커스텀 레이아웃 생성
        View dialogView = createDialogView();
        
        // 다이얼로그 빌드
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);
        builder.setCancelable(false); // 백키로 닫기 방지
        
        dialog = builder.create();
        
        // 제목 설정
        String displayName = callback.getBeaconDisplayName(mac);
        tvTitle.setText("거리보정 - " + displayName);
        
        // 기존 결과가 있으면 표시
        if (existingResult != null) {
            showExistingResult(existingResult);
        } else {
            setupInitialState();
        }
        
        dialog.show();
    }
    
    private View createDialogView() {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_calibration, null);
        
        // UI 컴포넌트 바인딩
        tvTitle = view.findViewById(R.id.tv_calibration_title);
        tvInstructions = view.findViewById(R.id.tv_instructions);
        layoutStages = view.findViewById(R.id.layout_stages);
        btnAction = view.findViewById(R.id.btn_action);
        btnCancel = view.findViewById(R.id.btn_cancel);
        layoutResult = view.findViewById(R.id.layout_result);
        tvResult = view.findViewById(R.id.tv_result);
        
        // 단계별 상태 텍스트 초기화
        stageStatusTexts = new TextView[3];
        stageStatusTexts[0] = view.findViewById(R.id.tv_stage1_status);
        stageStatusTexts[1] = view.findViewById(R.id.tv_stage2_status);
        stageStatusTexts[2] = view.findViewById(R.id.tv_stage3_status);
        
        // 버튼 리스너 설정
        btnCancel.setOnClickListener(v -> cancelCalibration());
        
        return view;
    }
    
    private void setupInitialState() {
        tvInstructions.setText("비콘으로부터 정확히 1m, 2m, 3m 떨어진 지점에서\n각각 측정을 진행합니다.");
        
        // 단계 상태 초기화
        for (int i = 0; i < stageStatusTexts.length; i++) {
            stageStatusTexts[i].setText(String.format("%dm: 대기중", i + 1));
        }
        
        btnAction.setText("측정 시작");
        btnAction.setOnClickListener(v -> startCalibration());
        
        layoutResult.setVisibility(View.GONE);
    }
    
    private void showExistingResult(CalibrationSession.CalibrationResult result) {
        tvInstructions.setText("이전 보정 결과:");
        
        // 단계들을 완료로 표시
        for (int i = 0; i < stageStatusTexts.length; i++) {
            stageStatusTexts[i].setText(String.format("%dm: 완료", i + 1));
        }
        
        showCalibrationResult(result);
        
        btnAction.setText("재측정");
        btnAction.setOnClickListener(v -> startCalibration());
    }
    
    private void startCalibration() {
        Log.d(TAG, "Starting calibration for MAC: " + mac);
        
        // 가드 활성화
        callback.onCalibrationStarted();
        
        // 세션 생성
        double[] distances = {1.0, 2.0, 3.0};
        session = new CalibrationSession(mac, distances);
        
        // UI 업데이트 시작
        startUiUpdates();
        
        // 첫 번째 단계 시작
        startStageCountdown(0);
        
        btnAction.setEnabled(false);
        layoutResult.setVisibility(View.GONE);
    }
    
    private void startStageCountdown(int stageIndex) {
        isCountingDown = true;
        countdownRemaining = COUNTDOWN_SECONDS;
        
        tvInstructions.setText(String.format("%dm 지점으로 이동하여 대기해주세요", stageIndex + 1));
        stageStatusTexts[stageIndex].setText(String.format("%dm: 준비중... %d", stageIndex + 1, countdownRemaining));
        
        Handler countdownHandler = new Handler();
        Runnable countdownRunnable = new Runnable() {
            @Override
            public void run() {
                countdownRemaining--;
                
                if (countdownRemaining > 0) {
                    stageStatusTexts[stageIndex].setText(String.format("%dm: 준비중... %d", stageIndex + 1, countdownRemaining));
                    countdownHandler.postDelayed(this, 1000);
                } else {
                    isCountingDown = false;
                    startStageCollection(stageIndex);
                }
            }
        };
        
        countdownHandler.postDelayed(countdownRunnable, 1000);
    }
    
    private void startStageCollection(int stageIndex) {
        tvInstructions.setText(String.format("%dm 지점에서 측정 중...\n비콘을 움직이지 마세요.", stageIndex + 1));
        
        Log.d(TAG, String.format("Starting stage %d collection", stageIndex + 1));
    }
    
    private void startUiUpdates() {
        uiUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateStageStatus();
                
                if (session != null && (session.isCollecting() || session.getStage() == CalibrationStage.COMPUTING)) {
                    uiHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
                }
            }
        };
        
        uiHandler.post(uiUpdateRunnable);
    }
    
    private void updateStageStatus() {
        if (session == null || isCountingDown) {
            return;
        }
        
        CalibrationStage stage = session.getStage();
        
        // 샘플 요청
        if (session.isCollecting()) {
            callback.onSampleNeeded(session);
        }
        
        // 현재 단계 상태 업데이트
        int currentStageIndex = getCurrentStageIndex(stage);
        if (currentStageIndex >= 0 && session.isCollecting()) {
            int sampleCount = session.getCurrentStageSampleCount();
            stageStatusTexts[currentStageIndex].setText(String.format("%dm: 수집중... (%d개)", 
                    currentStageIndex + 1, sampleCount));
            
            // 단계 완료 확인 및 다음 단계 진행
            if (session.isCurrentStageComplete()) {
                if (session.nextStageOrCompute()) {
                    // 다음 단계가 있으면 카운트다운 시작
                    CalibrationStage nextStage = session.getStage();
                    int nextStageIndex = getCurrentStageIndex(nextStage);
                    
                    // 현재 단계 완료 표시
                    stageStatusTexts[currentStageIndex].setText(String.format("%dm: 완료", currentStageIndex + 1));
                    
                    if (nextStageIndex >= 0 && session.isCollecting()) {
                        startStageCountdown(nextStageIndex);
                    }
                } else {
                    Log.e(TAG, "Failed to proceed to next stage");
                }
            }
        } else if (stage == CalibrationStage.COMPUTING) {
            tvInstructions.setText("데이터 분석 중...");
        } else if (stage == CalibrationStage.DONE) {
            onCalibrationComplete();
        }
    }
    
    private int getCurrentStageIndex(CalibrationStage stage) {
        switch (stage) {
            case STAGE_1M: return 0;
            case STAGE_2M: return 1;
            case STAGE_3M: return 2;
            default: return -1;
        }
    }
    
    private void onCalibrationComplete() {
        Log.d(TAG, "Calibration completed");
        
        CalibrationSession.CalibrationResult result = session.getResult();
        if (result != null) {
            // 모든 단계 완료 표시
            for (int i = 0; i < stageStatusTexts.length; i++) {
                stageStatusTexts[i].setText(String.format("%dm: 완료", i + 1));
            }
            
            showCalibrationResult(result);
        } else {
            tvInstructions.setText("측정 실패. 다시 시도해주세요.");
            btnAction.setText("재측정");
            btnAction.setEnabled(true);
            btnAction.setOnClickListener(v -> startCalibration());
        }
    }
    
    private void showCalibrationResult(CalibrationSession.CalibrationResult result) {
        layoutResult.setVisibility(View.VISIBLE);
        
        String ratingText;
        String ratingColor;
        String recommendation;
        
        switch (result.rating) {
            case GOOD:
                ratingText = "우수 (GOOD)";
                ratingColor = "#4CAF50"; // 녹색
                recommendation = "보정 결과를 저장하여 사용하세요.";
                break;
            case BORDERLINE:
                ratingText = "보통 (BORDERLINE)";
                ratingColor = "#FF9800"; // 주황색
                recommendation = "재측정을 권장하지만 저장하여 사용할 수 있습니다.";
                break;
            case BAD:
            default:
                ratingText = "불량 (BAD)";
                ratingColor = "#F44336"; // 빨간색
                recommendation = "재측정을 실행하세요.";
                break;
        }
        
        String resultText = String.format(
            "보정 상수:\n" +
            "• 1m 기준 RSSI: %.2f dBm\n" +
            "• 경로 손실 지수: %.2f\n\n" +
            "품질 평가:\n" +
            "• R² (결정계수): %.2f\n" +
            "• RMSE: %.2f dB\n" +
            "• 최대잔차: %.2f dB\n" +
            "• 판정: %s\n\n" +
            "%s",
            result.txPowerAt1m, result.pathLossExponent,
            result.rSquared, result.rmse, result.maxResidual,
            ratingText, recommendation
        );
        
        tvResult.setText(resultText);
        tvInstructions.setText("측정 완료");
        
        // 버튼 설정
        if (result.rating == QualityRating.BAD) {
            btnAction.setText("재측정");
            btnAction.setEnabled(true);
            btnAction.setOnClickListener(v -> startCalibration());
        } else {
            btnAction.setText("저장");
            btnAction.setEnabled(true);
            btnAction.setOnClickListener(v -> saveAndFinish(result));
        }
    }
    
    private void saveAndFinish(CalibrationSession.CalibrationResult result) {
        Log.d(TAG, "Saving calibration result for MAC: " + mac);
        
        callback.saveCalibrationResult(mac, result);
        callback.onCalibrationFinished(true);
        
        dialog.dismiss();
    }
    
    private void cancelCalibration() {
        Log.d(TAG, "Calibration cancelled for MAC: " + mac);
        
        if (session != null) {
            session.cancel();
        }
        
        if (uiUpdateRunnable != null) {
            uiHandler.removeCallbacks(uiUpdateRunnable);
        }
        
        callback.onCalibrationFinished(false);
        dialog.dismiss();
    }
}