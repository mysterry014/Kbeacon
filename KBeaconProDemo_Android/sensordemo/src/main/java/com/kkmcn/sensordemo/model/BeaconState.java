package com.kkmcn.sensordemo.model;

import java.util.Objects;

/**
 * 비콘 상태 정보 모델 클래스
 * - 비콘별 RSSI/거리 필터링 상태 저장
 * - 캘리브레이션 매개변수 보관
 * - 배터리/설정 정보 관리
 * 
 * Note: MAC 주소를 기반으로 객체 동일성 판단
 * Note: 광고 RSSI 기반 데이터, 연결 중 RSSI 미사용
 */
public class BeaconState {
    // 식별 정보
    private String name;
    private String mac;
    
    // RSSI/거리 상태
    private int lastRssi;
    private double rssiFiltered;
    private double distanceFiltered;
    
    // 배터리 정보
    private int batteryPercent;
    
    // 캘리브레이션 매개변수
    private double txPowerAt1m;
    private double pathLossExponent; // n 값
    
    // 사용자 설정
    private double distanceThresholdMeters;
    
    // 메타데이터
    private long updatedAt;
    
    /**
     * 기본 생성자
     */
    public BeaconState() {
        this.name = "";
        this.mac = "";
        this.lastRssi = 0;
        this.rssiFiltered = 0.0;
        this.distanceFiltered = 0.0;
        this.batteryPercent = 0;
        this.txPowerAt1m = -59.0; // 기본값
        this.pathLossExponent = 2.0; // 기본값
        this.distanceThresholdMeters = 50.0; // 기본 50m
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * MAC 주소로 비콘 상태 생성
     * @param mac MAC 주소 (필수)
     */
    public BeaconState(String mac) {
        this();
        this.mac = mac;
    }
    
    /**
     * 전체 정보로 비콘 상태 생성
     * @param name 비콘 이름
     * @param mac MAC 주소
     */
    public BeaconState(String name, String mac) {
        this(mac);
        this.name = name;
    }
    
    /**
     * RSSI/거리 상태 업데이트
     * @param rawRssi 원시 RSSI
     * @param rssiFiltered 필터링된 RSSI
     * @param distanceFiltered 필터링된 거리
     */
    public void updateSignalState(int rawRssi, double rssiFiltered, double distanceFiltered) {
        this.lastRssi = rawRssi;
        this.rssiFiltered = rssiFiltered;
        this.distanceFiltered = distanceFiltered;
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 캘리브레이션 매개변수 업데이트
     * @param txPowerAt1m 1미터 거리 RSSI
     * @param pathLossExponent 경로 손실 지수 (n)
     */
    public void updateCalibration(double txPowerAt1m, double pathLossExponent) {
        this.txPowerAt1m = txPowerAt1m;
        this.pathLossExponent = pathLossExponent;
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 거리 임계값 초과 여부 확인
     * @return true if 현재 거리 > 설정된 임계값
     */
    public boolean isDistanceExceeded() {
        return distanceFiltered > distanceThresholdMeters;
    }
    
    /**
     * 마지막 업데이트로부터 경과 시간 (밀리초)
     * @return 경과 시간 (ms)
     */
    public long getElapsedTimeSinceUpdate() {
        return System.currentTimeMillis() - updatedAt;
    }
    
    // Getter/Setter 메서드들
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public String getMac() {
        return mac;
    }
    
    public void setMac(String mac) {
        this.mac = mac;
    }
    
    public int getLastRssi() {
        return lastRssi;
    }
    
    public void setLastRssi(int lastRssi) {
        this.lastRssi = lastRssi;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public double getRssiFiltered() {
        return rssiFiltered;
    }
    
    public void setRssiFiltered(double rssiFiltered) {
        this.rssiFiltered = rssiFiltered;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public double getDistanceFiltered() {
        return distanceFiltered;
    }
    
    public void setDistanceFiltered(double distanceFiltered) {
        this.distanceFiltered = distanceFiltered;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public int getBatteryPercent() {
        return batteryPercent;
    }
    
    public void setBatteryPercent(int batteryPercent) {
        this.batteryPercent = batteryPercent;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public double getTxPowerAt1m() {
        return txPowerAt1m;
    }
    
    public void setTxPowerAt1m(double txPowerAt1m) {
        this.txPowerAt1m = txPowerAt1m;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public double getPathLossExponent() {
        return pathLossExponent;
    }
    
    public void setPathLossExponent(double pathLossExponent) {
        this.pathLossExponent = pathLossExponent;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public double getDistanceThresholdMeters() {
        return distanceThresholdMeters;
    }
    
    public void setDistanceThreshold(double distanceThresholdMeters) {
        this.distanceThresholdMeters = distanceThresholdMeters;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public void setDistanceThresholdMeters(double distanceThresholdMeters) {
        this.distanceThresholdMeters = distanceThresholdMeters;
        this.updatedAt = System.currentTimeMillis();
    }
    
    public long getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BeaconState that = (BeaconState) o;
        return Objects.equals(mac, that.mac);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(mac);
    }
    
    @Override
    public String toString() {
        return String.format(
            "BeaconState{name='%s', mac='%s', rssi=%.1f, distance=%.1fm, battery=%d%%, threshold=%.1fm}",
            name, mac, rssiFiltered, distanceFiltered, batteryPercent, distanceThresholdMeters
        );
    }
    
    /**
     * 상세 상태 정보 반환 (디버깅용)
     * @return 상세 상태 문자열
     */
    public String toDetailedString() {
        return String.format(
            "BeaconState{\n" +
            "  name='%s', mac='%s'\n" +
            "  lastRssi=%d, rssiFiltered=%.1f\n" +
            "  distanceFiltered=%.1fm, threshold=%.1fm\n" +
            "  battery=%d%%, txPower=%.1f, n=%.2f\n" +
            "  exceeded=%b, updatedAt=%d\n" +
            "}",
            name, mac, lastRssi, rssiFiltered,
            distanceFiltered, distanceThresholdMeters,
            batteryPercent, txPowerAt1m, pathLossExponent,
            isDistanceExceeded(), updatedAt
        );
    }
    
    // TODO: 추후 확장 포인트
    // - JSON 직렬화/역직렬화 지원
    // - 상태 변화 히스토리 추적
    // - 알람 상태 정보 추가
    // - 연결 상태 정보 추가
    // - 통계 정보 (평균 RSSI, 최대/최소 거리 등)
}