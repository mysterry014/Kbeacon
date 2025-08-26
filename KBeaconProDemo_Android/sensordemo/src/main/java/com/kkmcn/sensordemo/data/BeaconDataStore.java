package com.kkmcn.sensordemo.data;

import com.kkmcn.sensordemo.model.BeaconState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비콘 데이터 저장소 (싱글톤)
 * - 스레드 안전한 비콘 상태 관리
 * - MAC 주소 기반 비콘 식별
 * - CRUD 연산 지원
 * 
 * 특징:
 * - ConcurrentHashMap을 통한 멀티스레드 안전성
 * - 최대 저장 용량 제한 (기본 100개, 최소 20개 보장)
 * - 자동 정리 기능 (LRU 방식)
 * 
 * Note: 광고 RSSI 기반 데이터 저장, 연결 중 RSSI 미사용
 */
public class BeaconDataStore {
    private static final int DEFAULT_MAX_CAPACITY = 100;
    private static final int MINIMUM_CAPACITY = 20;
    
    private static volatile BeaconDataStore instance;
    private final ConcurrentHashMap<String, BeaconState> beacons;
    private final int maxCapacity;
    
    /**
     * private 생성자 (싱글톤 패턴)
     */
    private BeaconDataStore() {
        this(DEFAULT_MAX_CAPACITY);
    }
    
    /**
     * 용량 지정 생성자 (테스트용)
     * @param maxCapacity 최대 저장 용량
     */
    private BeaconDataStore(int maxCapacity) {
        this.maxCapacity = Math.max(maxCapacity, MINIMUM_CAPACITY);
        this.beacons = new ConcurrentHashMap<>(this.maxCapacity);
    }
    
    /**
     * 싱글톤 인스턴스 반환
     * @return BeaconDataStore 인스턴스
     */
    public static BeaconDataStore getInstance() {
        if (instance == null) {
            synchronized (BeaconDataStore.class) {
                if (instance == null) {
                    instance = new BeaconDataStore();
                }
            }
        }
        return instance;
    }
    
    /**
     * MAC 주소로 비콘 상태 조회
     * @param mac MAC 주소
     * @return BeaconState 또는 null
     */
    public BeaconState get(String mac) {
        if (mac == null || mac.isEmpty()) {
            return null;
        }
        return beacons.get(mac);
    }
    
    /**
     * 비콘 상태 저장/업데이트
     * - 존재하지 않으면 새로 생성
     * - 존재하면 업데이트
     * @param beaconState 저장할 비콘 상태
     * @return 이전 상태 (없으면 null)
     */
    public BeaconState upsert(BeaconState beaconState) {
        if (beaconState == null || beaconState.getMac() == null || beaconState.getMac().isEmpty()) {
            return null;
        }
        
        // 용량 초과 시 자동 정리
        if (beacons.size() >= maxCapacity && !beacons.containsKey(beaconState.getMac())) {
            cleanupOldestEntries();
        }
        
        return beacons.put(beaconState.getMac(), beaconState);
    }
    
    /**
     * 모든 비콘 상태 반환
     * @return BeaconState 리스트 (복사본)
     */
    public List<BeaconState> getAll() {
        return new ArrayList<>(beacons.values());
    }
    
    /**
     * 이름 필터로 비콘 상태 조회
     * @param namePattern 이름 패턴 (정규식)
     * @return 패턴에 맞는 BeaconState 리스트
     */
    public List<BeaconState> getByNamePattern(String namePattern) {
        if (namePattern == null || namePattern.isEmpty()) {
            return getAll();
        }
        
        List<BeaconState> filtered = new ArrayList<>();
        for (BeaconState beacon : beacons.values()) {
            if (beacon.getName() != null && beacon.getName().matches(namePattern)) {
                filtered.add(beacon);
            }
        }
        return filtered;
    }
    
    /**
     * 6자리 숫자로 시작하는 비콘 조회 (CLAUDE.md 요구사항)
     * @return 6자리 숫자로 시작하는 BeaconState 리스트
     */
    public List<BeaconState> getValidBeacons() {
        return getByNamePattern("^\\d{6}_.*");
    }
    
    /**
     * 거리 임계값 초과 비콘 조회
     * @return 임계값 초과 BeaconState 리스트
     */
    public List<BeaconState> getDistanceExceededBeacons() {
        List<BeaconState> exceeded = new ArrayList<>();
        for (BeaconState beacon : beacons.values()) {
            if (beacon.isDistanceExceeded()) {
                exceeded.add(beacon);
            }
        }
        return exceeded;
    }
    
    /**
     * MAC 주소로 비콘 제거
     * @param mac MAC 주소
     * @return 제거된 BeaconState (없으면 null)
     */
    public BeaconState remove(String mac) {
        if (mac == null || mac.isEmpty()) {
            return null;
        }
        return beacons.remove(mac);
    }
    
    /**
     * 모든 비콘 상태 초기화
     */
    public void clear() {
        beacons.clear();
    }
    
    /**
     * 저장된 비콘 개수 반환
     * @return 비콘 개수
     */
    public int size() {
        return beacons.size();
    }
    
    /**
     * 비어있는지 확인
     * @return true if 비콘 없음
     */
    public boolean isEmpty() {
        return beacons.isEmpty();
    }
    
    /**
     * 특정 MAC이 존재하는지 확인
     * @param mac MAC 주소
     * @return true if 존재함
     */
    public boolean contains(String mac) {
        return mac != null && !mac.isEmpty() && beacons.containsKey(mac);
    }
    
    /**
     * 가장 오래된 항목들 정리 (LRU 방식)
     */
    private void cleanupOldestEntries() {
        if (beacons.size() < maxCapacity) {
            return;
        }
        
        // updatedAt 기준으로 정렬하여 오래된 항목들 찾기
        List<BeaconState> sorted = new ArrayList<>(beacons.values());
        sorted.sort((a, b) -> Long.compare(a.getUpdatedAt(), b.getUpdatedAt()));
        
        // 상위 25% 제거
        int removeCount = Math.max(1, beacons.size() / 4);
        for (int i = 0; i < removeCount && i < sorted.size(); i++) {
            beacons.remove(sorted.get(i).getMac());
        }
    }
    
    /**
     * 오래된 항목들 정리 (지정된 시간 기준)
     * @param maxAgeMillis 최대 보관 시간 (밀리초)
     * @return 제거된 항목 수
     */
    public int cleanupOldEntries(long maxAgeMillis) {
        if (maxAgeMillis <= 0) {
            return 0;
        }
        
        long cutoffTime = System.currentTimeMillis() - maxAgeMillis;
        List<String> toRemove = new ArrayList<>();
        
        for (BeaconState beacon : beacons.values()) {
            if (beacon.getUpdatedAt() < cutoffTime) {
                toRemove.add(beacon.getMac());
            }
        }
        
        for (String mac : toRemove) {
            beacons.remove(mac);
        }
        
        return toRemove.size();
    }
    
    /**
     * 저장소 상태 정보 반환 (디버깅용)
     * @return 상태 문자열
     */
    public String getDebugInfo() {
        return String.format(
            "BeaconDataStore[size=%d, maxCapacity=%d, validBeacons=%d, exceededBeacons=%d]",
            beacons.size(), maxCapacity, getValidBeacons().size(), getDistanceExceededBeacons().size()
        );
    }
    
    // TODO: 추후 확장 포인트
    // - SharedPreferences 연동을 통한 영속화
    // - JSON 기반 백업/복원
    // - 실시간 변화 알림 (Observer 패턴)
    // - 통계 정보 API (평균 거리, 배터리 상태 등)
    // - 배치 업데이트 지원
    // - 메모리 사용량 최적화
}