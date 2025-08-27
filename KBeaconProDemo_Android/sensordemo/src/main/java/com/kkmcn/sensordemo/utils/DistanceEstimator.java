package com.kkmcn.sensordemo.utils;

/**
 * RSSI 기반 거리 추정 클래스
 * - RSSI-거리 변환 공식 사용
 * - 지수이동평균(EMA)을 통한 거리 스무딩
 * - 캘리브레이션 매개변수 지원
 * 
 * 공식: distance(m) = 10^((txPowerAt1m - rssiFiltered)/(10 * n))
 * 역함수: RSSI(d) = txPowerAt1m - 10 * n * log10(d)
 * 
 * 설정:
 * - 기본 txPowerAt1m: -59 dBm
 * - 기본 n: 2.0 (경로 손실 지수)
 * - EMA 베타: β=0.30
 * 
 * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI는 미사용
 */
public class DistanceEstimator {
    private static final double DEFAULT_TX_POWER_AT_1M = -59.0; // dBm
    private static final double DEFAULT_PATH_LOSS_EXPONENT = 2.0;
    private static final double DEFAULT_EMA_BETA = 0.30;
    
    private double txPowerAt1m;
    private double pathLossExponent; // n 값
    private final double emaBeta;
    private double filteredDistance;
    private boolean initialized;
    
    /**
     * 기본 설정으로 거리 추정기 생성
     */
    public DistanceEstimator() {
        this(DEFAULT_TX_POWER_AT_1M, DEFAULT_PATH_LOSS_EXPONENT, DEFAULT_EMA_BETA);
    }
    
    /**
     * 사용자 정의 설정으로 거리 추정기 생성
     * @param txPowerAt1m 1미터 거리에서의 RSSI (dBm)
     * @param pathLossExponent 경로 손실 지수 (n)
     * @param emaBeta EMA 베타 계수 (0~1)
     */
    public DistanceEstimator(double txPowerAt1m, double pathLossExponent, double emaBeta) {
        this.txPowerAt1m = txPowerAt1m;
        this.pathLossExponent = pathLossExponent;
        this.emaBeta = emaBeta;
        this.filteredDistance = 0.0;
        this.initialized = false;
    }
    
    /**
     * RSSI로부터 거리 추정
     * @param rssiFiltered 필터링된 RSSI 값 (dBm)
     * @return 추정된 거리 (미터)
     */
    public synchronized double estimate(double rssiFiltered) {
        // 1. RSSI-거리 변환 공식 적용
        double rawDistance = calculateRawDistance(rssiFiltered);
        
        // 2. EMA 적용하여 거리 스무딩
        if (!initialized) {
            filteredDistance = rawDistance;
            initialized = true;
        } else {
            // EMA 공식: distance_t = β * raw_distance + (1-β) * distance_{t-1}
            filteredDistance = emaBeta * rawDistance + (1 - emaBeta) * filteredDistance;
        }
        
        return filteredDistance;
    }
    
    /**
     * 현재 필터링된 거리 값 반환
     * @return 필터링된 거리 (미터)
     */
    public synchronized double getFilteredDistance() {
        return filteredDistance;
    }
    
    /**
     * 원시 거리 계산 (EMA 적용 전)
     * @param rssiFiltered 필터링된 RSSI 값
     * @return 원시 거리 (미터)
     */
    public synchronized double calculateRawDistance(double rssiFiltered) {
        if (rssiFiltered >= txPowerAt1m) {
            return 0.1; // 최소 거리 0.1m
        }
        
        // distance = 10^((txPowerAt1m - rssiFiltered)/(10 * n))
        double exponent = (txPowerAt1m - rssiFiltered) / (10.0 * pathLossExponent);
        double distance = Math.pow(10, exponent);
        
        // 합리적 범위로 제한 (0.1m ~ 100m)
        return Math.max(0.1, Math.min(100.0, distance));
    }
    
    /**
     * 거리로부터 예상 RSSI 계산 (역함수)
     * @param distance 거리 (미터)
     * @return 예상 RSSI (dBm)
     */
    public synchronized double calculateExpectedRssi(double distance) {
        if (distance <= 0) {
            return txPowerAt1m;
        }
        
        // RSSI(d) = txPowerAt1m - 10 * n * log10(d)
        return txPowerAt1m - 10.0 * pathLossExponent * Math.log10(distance);
    }
    
    /**
     * 캘리브레이션 매개변수 업데이트
     * @param txPowerAt1m 1미터 거리에서의 RSSI (dBm)
     * @param pathLossExponent 경로 손실 지수 (n)
     */
    public synchronized void updateCalibration(double txPowerAt1m, double pathLossExponent) {
        this.txPowerAt1m = txPowerAt1m;
        this.pathLossExponent = pathLossExponent;
    }
    
    /**
     * 캘리브레이션 설정 (CalibrationDialog에서 사용)
     * @param txPowerAt1m 1미터 거리에서의 RSSI (dBm)
     * @param pathLossExponent 경로 손실 지수 (n)
     */
    public synchronized void setCalibration(double txPowerAt1m, double pathLossExponent) {
        this.txPowerAt1m = txPowerAt1m;
        this.pathLossExponent = pathLossExponent;
        // 필터는 초기화하지 않음 - 연속적인 거리 추정 유지
    }
    
    /**
     * 필터 초기화
     */
    public synchronized void reset() {
        filteredDistance = 0.0;
        initialized = false;
    }
    
    /**
     * 추정기가 초기화되었는지 확인
     * @return true if 최소 1회 추정 수행됨
     */
    public synchronized boolean isInitialized() {
        return initialized;
    }
    
    // Getter 메서드들
    public synchronized double getTxPowerAt1m() {
        return txPowerAt1m;
    }
    
    public synchronized double getPathLossExponent() {
        return pathLossExponent;
    }
    
    public synchronized double getEmaBeta() {
        return emaBeta;
    }
    
    /**
     * 추정기 상태 정보 반환 (디버깅용)
     * @return 상태 문자열
     */
    public synchronized String getDebugInfo() {
        return String.format(
            "DistanceEstimator[distance=%.1fm, txPower=%.1f, n=%.2f, initialized=%b]",
            filteredDistance, txPowerAt1m, pathLossExponent, initialized
        );
    }
    
    // TODO: 추후 확장 포인트
    // - 환경별 적응적 캘리브레이션
    // - 다중 경로 효과 보상
    // - 온도/습도 보정 계수
    // - 신뢰도 지표 계산
    // - 머신러닝 기반 거리 추정
}