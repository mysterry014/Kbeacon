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
    private String name;        // 광고 이름 (비콘에서 브로드캠스트된 이름)
    private String aliasName;   // 별칭 (사용자 로컬 이름, nullable)
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
    
    // 알람 상태 관리 (Command Gate 패턴용)
    public volatile boolean desiredRing; // 희망하는 부저 상태
    
    // 캘리브레이션 진행 상태 (UI 업데이트용)
    private boolean calibrationInProgress = false;
    private int calibrationStage = 0; // 1, 2, 3 (1-based index)
    
    // 메타데이터
    private long updatedAt;
    
    /**
     * 기본 생성자
     * 초기값을 NaN으로 설정하여 0.0과 실제 측정값 구분
     */
    public BeaconState() {
        this.name = "";
        this.mac = "";
        this.lastRssi = 0;
        this.rssiFiltered = Double.NaN;
        this.distanceFiltered = Double.NaN;
        this.batteryPercent = -1; // -1은 알 수 없음 의미
        this.txPowerAt1m = Double.NaN; // 캘리브레이션 안 됨 상태
        this.pathLossExponent = Double.NaN; // 캘리브레이션 안 됨 상태
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
    
    /**
     * 표시용 이름 (별칭 우선, 없으면 광고 이름)
     * @return 표시할 이름
     */
    public String getDisplayName() {
        return aliasName != null ? aliasName : name;
    }
    
    /**
     * 별칭 조회
     * @return 별칭 (null 가능)
     */
    public String getAliasName() {
        return aliasName;
    }
    
    /**
     * 별칭 설정
     * @param alias 별칭 (빈 문자열이면 null로 설정)
     */
    public void setAliasName(String alias) {
        this.aliasName = (alias != null && alias.trim().isEmpty()) ? null : alias;
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
    
    // ========== BleService에서 사용하는 추가 메서드들 ==========
    
    // 광고 이름 관리
    private String advertisedName; // 광고에서 받은 원본 이름
    
    /**
     * 광고된 이름 설정 (BLE 광고에서 받은 원본 이름)
     * @param advertisedName 광고 이름
     */
    public void setAdvertisedName(String advertisedName) {
        this.advertisedName = advertisedName;
        // 기본 name이 없으면 광고 이름으로 설정
        if (this.name == null || this.name.isEmpty()) {
            this.name = advertisedName;
        }
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 광고된 이름 조회
     * @return 광고 이름
     */
    public String getAdvertisedName() {
        return advertisedName;
    }
    
    // 배터리 관련
    private float batteryVoltage;
    private long lastBatteryUpdateTime;
    
    /**
     * 배터리 전압 설정
     * @param voltage 배터리 전압 (V)
     */
    public void setBatteryVoltage(float voltage) {
        this.batteryVoltage = voltage;
        this.lastBatteryUpdateTime = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 배터리 전압 조회
     * @return 배터리 전압 (V)
     */
    public float getBatteryVoltage() {
        return batteryVoltage;
    }
    
    /**
     * 마지막 배터리 업데이트 시간 설정
     * @param updateTime 업데이트 시간 (밀리초)
     */
    public void setLastBatteryUpdateTime(long updateTime) {
        this.lastBatteryUpdateTime = updateTime;
    }
    
    /**
     * 마지막 배터리 업데이트 시간 조회
     * @return 업데이트 시간 (밀리초)
     */
    public long getLastBatteryUpdateTime() {
        return lastBatteryUpdateTime;
    }
    
    // 일반적인 업데이트 시간 관리
    /**
     * 마지막 업데이트 시간 설정 (일반적인 상태 변경)
     * @param updateTime 업데이트 시간 (밀리초)
     */
    public void setLastUpdateTime(long updateTime) {
        this.updatedAt = updateTime;
    }
    
    /**
     * 마지막 업데이트 시간 조회
     * @return 업데이트 시간 (밀리초)
     */
    public long getLastUpdateTime() {
        return updatedAt;
    }
    
    // 별칭 관리 (BleService용 메서드명)
    /**
     * 별칭 설정 (BleService 호환용 메서드)
     * @param alias 별칭
     */
    public void setAlias(String alias) {
        setAliasName(alias);
    }
    
    /**
     * 별칭 조회 (BleService 호환용 메서드)
     * @return 별칭
     */
    public String getAlias() {
        return getAliasName();
    }
    
    /**
     * 거리 설정값 조회 (BleService 호환용 메서드)
     * @return 거리 설정값 (m)
     */
    public double getDistanceThreshold() {
        return distanceThresholdMeters;
    }
    
    // ========== 캘리브레이션 진행 상태 관리 ==========
    
    /**
     * 캘리브레이션 진행 상태 설정
     * @param inProgress 진행 여부
     */
    public void setCalibrationInProgress(boolean inProgress) {
        this.calibrationInProgress = inProgress;
        if (!inProgress) {
            this.calibrationStage = 0; // 완료 시 단계 리셋
        }
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 캘리브레이션 진행 상태 조회
     * @return 진행 여부
     */
    public boolean isCalibrationInProgress() {
        return calibrationInProgress;
    }
    
    /**
     * 캘리브레이션 단계 설정
     * @param stage 현재 단계 (1, 2, 3)
     */
    public void setCalibrationStage(int stage) {
        this.calibrationStage = stage;
        this.updatedAt = System.currentTimeMillis();
    }
    
    /**
     * 캘리브레이션 단계 조회
     * @return 현재 단계 (0=비활성, 1-3=단계)
     */
    public int getCalibrationStage() {
        return calibrationStage;
    }
    
    /**
     * 캘리브레이션 상태 메시지 생성 (UI용)
     * @return 상태 메시지
     */
    public String getCalibrationStatusMessage() {
        if (!calibrationInProgress) {
            return "";
        }
        if (calibrationStage > 0) {
            return String.format("보정 진행 (%d/3 단계)", calibrationStage);
        } else {
            return "보정 준비 중";
        }
    }
    
    /**
     * 캘리브레이션 유효성 검사
     * @return 캘리브레이션이 완료되어 유효한 값을 가지고 있는지 여부
     */
    public boolean hasValidCalibration() {
        return Double.isFinite(txPowerAt1m) && Double.isFinite(pathLossExponent) && pathLossExponent > 0.0;
    }
    
    /**
     * RSSI 값 유효성 검사
     * @return RSSI 값이 유효한지 여부
     */
    public boolean hasValidRssi() {
        return Double.isFinite(rssiFiltered);
    }
    
    /**
     * 거리 값 유효성 검사
     * @return 거리 값이 유효한지 여부
     */
    public boolean hasValidDistance() {
        return Double.isFinite(distanceFiltered) && distanceFiltered > 0.0;
    }
    
    /**
     * 배터리 정보 유효성 검사
     * @return 배터리 정보가 유효한지 여부
     */
    public boolean hasValidBattery() {
        return batteryPercent >= 0 && batteryPercent <= 100;
    }
    
    // TODO: 추후 확장 포인트
    // - JSON 직렬화/역직렬화 지원
    // - 상태 변화 히스토리 추적
    // - 알람 상태 정보 추가
    // - 연결 상태 정보 추가
    // - 통계 정보 (평균 RSSI, 최대/최소 거리 등)
}