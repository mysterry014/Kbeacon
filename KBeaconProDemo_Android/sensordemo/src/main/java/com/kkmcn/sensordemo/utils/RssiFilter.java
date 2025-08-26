package com.kkmcn.sensordemo.utils;

import java.util.List;

/**
 * RSSI 신호 필터링 클래스
 * - 이동평균 윈도우를 통한 이상치 제거
 * - 지수이동평균(EMA)을 통한 RSSI 스무딩
 * 
 * 설정:
 * - 윈도우 크기: N=10
 * - 이상치 임계값: ±7dB (중앙값 기준)
 * - EMA 알파: α=0.25
 * 
 * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI는 미사용
 */
public class RssiFilter {
    private static final int DEFAULT_WINDOW_SIZE = 10;
    private static final double DEFAULT_OUTLIER_THRESHOLD_DB = 7.0;
    private static final double DEFAULT_EMA_ALPHA = 0.25;
    
    private final RssiWindow window;
    private final double emaAlpha;
    private double filteredRssi;
    private boolean initialized;
    
    /**
     * 기본 설정으로 RSSI 필터 생성
     */
    public RssiFilter() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_OUTLIER_THRESHOLD_DB, DEFAULT_EMA_ALPHA);
    }
    
    /**
     * 사용자 정의 설정으로 RSSI 필터 생성
     * @param windowSize 이동평균 윈도우 크기
     * @param outlierThresholdDb 이상치 임계값 (dB)
     * @param emaAlpha EMA 알파 계수 (0~1)
     */
    public RssiFilter(int windowSize, double outlierThresholdDb, double emaAlpha) {
        this.window = new RssiWindow(windowSize, outlierThresholdDb);
        this.emaAlpha = emaAlpha;
        this.filteredRssi = 0.0;
        this.initialized = false;
    }
    
    /**
     * 새 RSSI 샘플 추가 및 필터링된 값 계산
     * @param rawRssi 원시 RSSI 값 (dBm)
     * @return 필터링된 RSSI 값 (dBm)
     */
    public synchronized double addSample(int rawRssi) {
        // 1. 윈도우에 샘플 추가
        window.addSample(rawRssi);
        
        // 2. 이상치 제거된 샘플들 가져오기
        List<Integer> filteredSamples = window.getFilteredSamples();
        
        // 3. 이상치 제거된 샘플들의 평균 계산
        double cleanRssi = calculateAverage(filteredSamples);
        
        // 4. EMA 적용
        if (!initialized) {
            filteredRssi = cleanRssi;
            initialized = true;
        } else {
            // EMA 공식: filtered_t = α * clean_rssi + (1-α) * filtered_{t-1}
            filteredRssi = emaAlpha * cleanRssi + (1 - emaAlpha) * filteredRssi;
        }
        
        return filteredRssi;
    }
    
    /**
     * 현재 필터링된 RSSI 값 반환
     * @return 필터링된 RSSI 값 (dBm)
     */
    public synchronized double getFilteredRssi() {
        return filteredRssi;
    }
    
    /**
     * 필터 초기화
     */
    public synchronized void reset() {
        window.clear();
        filteredRssi = 0.0;
        initialized = false;
    }
    
    /**
     * 필터가 초기화되었는지 확인
     * @return true if 최소 1개 샘플 처리됨
     */
    public synchronized boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 현재 윈도우 크기 반환
     * @return 저장된 샘플 개수
     */
    public synchronized int getWindowSize() {
        return window.size();
    }
    
    /**
     * 정수 리스트의 평균 계산
     * @param samples 정수 리스트
     * @return 평균값
     */
    private double calculateAverage(List<Integer> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        
        long sum = 0;
        for (Integer sample : samples) {
            sum += sample;
        }
        
        return (double) sum / samples.size();
    }
    
    /**
     * 필터 상태 정보 반환 (디버깅용)
     * @return 상태 문자열
     */
    public synchronized String getDebugInfo() {
        return String.format(
            "RssiFilter[filtered=%.1f, samples=%d, initialized=%b]",
            filteredRssi, window.size(), initialized
        );
    }
    
    // TODO: 추후 확장 포인트
    // - 적응적 알파 계수 조정
    // - 다양한 필터 타입 지원 (Kalman, Butterworth 등)
    // - 신호 품질 지표 계산
    // - 실시간 성능 모니터링
}