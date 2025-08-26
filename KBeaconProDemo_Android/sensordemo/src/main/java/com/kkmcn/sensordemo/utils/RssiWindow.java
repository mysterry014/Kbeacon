package com.kkmcn.sensordemo.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 고정 크기 RSSI 윈도우 관리 및 통계 헬퍼 클래스
 * - 최근 N개 샘플 유지
 * - 중앙값, 분산 계산
 * - 이상치 판단 (중앙값 ±7dB 기준)
 * 
 * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI는 미사용
 */
public class RssiWindow {
    private final int maxSize;
    private final List<Integer> samples;
    private final double outlierThresholdDb;
    
    /**
     * RSSI 윈도우 생성
     * @param maxSize 최대 샘플 개수 (권장: 10)
     * @param outlierThresholdDb 이상치 판단 임계값 (권장: 7.0dB)
     */
    public RssiWindow(int maxSize, double outlierThresholdDb) {
        this.maxSize = maxSize;
        this.outlierThresholdDb = outlierThresholdDb;
        this.samples = new ArrayList<>(maxSize);
    }
    
    /**
     * 새 RSSI 샘플 추가
     * @param rssi RSSI 값 (dBm)
     */
    public synchronized void addSample(int rssi) {
        if (samples.size() >= maxSize) {
            samples.remove(0); // 가장 오래된 샘플 제거
        }
        samples.add(rssi);
    }
    
    /**
     * 현재 윈도우의 중앙값 계산
     * @return 중앙값 (dBm), 샘플 없으면 0
     */
    public synchronized double getMedian() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        
        List<Integer> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        
        int size = sorted.size();
        if (size % 2 == 0) {
            return (sorted.get(size/2 - 1) + sorted.get(size/2)) / 2.0;
        } else {
            return sorted.get(size/2);
        }
    }
    
    /**
     * 이상치가 아닌 샘플들의 리스트 반환
     * @return 중앙값 ±outlierThresholdDb 범위 내 샘플들
     */
    public synchronized List<Integer> getFilteredSamples() {
        if (samples.size() < 3) {
            return new ArrayList<>(samples); // 샘플 부족시 모든 샘플 반환
        }
        
        double median = getMedian();
        List<Integer> filtered = new ArrayList<>();
        
        for (Integer sample : samples) {
            if (Math.abs(sample - median) <= outlierThresholdDb) {
                filtered.add(sample);
            }
        }
        
        return filtered.isEmpty() ? new ArrayList<>(samples) : filtered;
    }
    
    /**
     * 특정 RSSI가 이상치인지 판단
     * @param rssi 검사할 RSSI 값
     * @return true if 이상치
     */
    public synchronized boolean isOutlier(int rssi) {
        if (samples.size() < 3) {
            return false; // 샘플 부족시 이상치 아님으로 간주
        }
        
        double median = getMedian();
        return Math.abs(rssi - median) > outlierThresholdDb;
    }
    
    /**
     * 현재 윈도우 크기
     * @return 저장된 샘플 개수
     */
    public synchronized int size() {
        return samples.size();
    }
    
    /**
     * 윈도우 초기화
     */
    public synchronized void clear() {
        samples.clear();
    }
    
    /**
     * 현재 샘플들의 복사본 반환 (디버깅용)
     * @return 샘플 리스트 복사본
     */
    public synchronized List<Integer> getSamples() {
        return new ArrayList<>(samples);
    }
    
    // TODO: 추후 확장 포인트
    // - 분산/표준편차 계산
    // - Z-score 기반 이상치 탐지
    // - 동적 임계값 조정
}