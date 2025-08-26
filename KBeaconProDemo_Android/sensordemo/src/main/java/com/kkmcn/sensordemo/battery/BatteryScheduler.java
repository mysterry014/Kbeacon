package com.kkmcn.sensordemo.battery;

import android.util.Log;

import com.kkmcn.kbeaconlib2.KBConnState;
import com.kkmcn.kbeaconlib2.KBeacon;
import com.kkmcn.kbeaconlib2.KBeaconsMgr;
import com.kkmcn.sensordemo.DeviceScanActivity;
import com.kkmcn.sensordemo.data.BeaconDataStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 비콘별 배터리 수준 자동 조회 스케줄러
 * - 신규 비콘 탐지 시 즉시 배터리 조회
 * - 1시간 주기로 정기적인 배터리 상태 업데이트
 * - 실패 시 지수 백오프 적용 (최대 30분)
 * 
 * Note: KBeacon 표준 배터리 읽기 API 사용, 광고 RSSI 기반 설계
 */
public class BatteryScheduler {
    private static final String TAG = "BATT";
    
    private final ScheduledExecutorService mExecutor;
    private final KBeaconsMgr mBeaconsMgr;
    private final BeaconDataStore mDataStore;
    private final DeviceScanActivity mActivity; // 저장 훅 호출용
    
    // 비콘별 스케줄 정보
    private final ConcurrentHashMap<String, Long> mNextPollEpochMs;
    private final ConcurrentHashMap<String, Integer> mBackoffSeconds;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> mScheduledTasks;
    
    // 설정값들
    private static final long POLL_INTERVAL_MS = 60 * 60 * 1000L;  // 1시간
    private static final int BACKOFF_MIN_SEC = 30;                 // 최소 백오프 30초
    private static final int BACKOFF_MAX_SEC = 30 * 60;           // 최대 백오프 30분
    private static final int CONNECTION_TIMEOUT_MS = 15000;        // 연결 타임아웃 15초
    
    /**
     * BatteryScheduler 생성자
     * @param beaconsMgr KBeaconsMgr 인스턴스
     * @param dataStore BeaconDataStore 인스턴스  
     * @param activity DeviceScanActivity 인스턴스 (저장 훅 호출용)
     */
    public BatteryScheduler(KBeaconsMgr beaconsMgr, BeaconDataStore dataStore, DeviceScanActivity activity) {
        mExecutor = Executors.newSingleThreadScheduledExecutor();
        mBeaconsMgr = beaconsMgr;
        mDataStore = dataStore;
        mActivity = activity;
        
        mNextPollEpochMs = new ConcurrentHashMap<>();
        mBackoffSeconds = new ConcurrentHashMap<>();
        mScheduledTasks = new ConcurrentHashMap<>();
        
        Log.d(TAG, "BatteryScheduler initialized");
    }
    
    /**
     * 비콘이 관측되었음을 알림 (신규/오래된 비콘의 경우 배터리 조회 스케줄)
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     */
    public void onSeen(String mac, String name) {
        if (mac == null || mac.isEmpty()) {
            Log.w(TAG, "Invalid MAC for battery schedule");
            return;
        }
        
        long now = System.currentTimeMillis();
        Long nextPoll = mNextPollEpochMs.get(mac);
        
        // 신규 비콘이거나 다음 폴링 시간이 도래한 경우
        boolean shouldPoll = (nextPoll == null) || (now >= nextPoll);
        
        if (shouldPoll) {
            Log.d(TAG, "Scheduling battery poll for " + (name != null ? name : mac));
            
            // 기존 작업 취소
            ScheduledFuture<?> existingTask = mScheduledTasks.get(mac);
            if (existingTask != null) {
                existingTask.cancel(false);
            }
            
            // 즉시 배터리 조회 스케줄 (최초 탐지) 또는 약간의 딜레이 (정기 조회)
            int delaySeconds = (nextPoll == null) ? 1 : 5; // 신규는 1초, 정기는 5초 후
            
            ScheduledFuture<?> task = mExecutor.schedule(
                () -> pollBattery(mac, name), 
                delaySeconds, 
                TimeUnit.SECONDS
            );
            
            mScheduledTasks.put(mac, task);
        }
    }
    
    /**
     * 배터리 수준 조회 실행 (내부 메서드)
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     */
    private void pollBattery(String mac, String name) {
        try {
            Log.d(TAG, "Polling battery for " + (name != null ? name : mac));
            
            // KBeacon 인스턴스 조회
            KBeacon beacon = mBeaconsMgr.getBeacon(mac);
            if (beacon == null) {
                Log.w(TAG, "Beacon not found for battery poll: " + mac);
                scheduleRetryWithBackoff(mac, name);
                return;
            }
            
            // 연결 상태 확인 및 연결 시도
            if (beacon.getState() != KBConnState.Connected) {
                Log.d(TAG, "Connecting for battery poll: " + (name != null ? name : mac));
                
                beacon.connect(null, CONNECTION_TIMEOUT_MS, new KBeacon.ConnStateDelegate() {
                    @Override
                    public void onConnStateChange(KBeacon beacon, KBConnState state, int nReason) {
                        if (state == KBConnState.Connected) {
                            Log.d(TAG, "Connected, reading battery: " + (name != null ? name : mac));
                            readBatteryLevel(mac, name, beacon);
                        } else if (state == KBConnState.Disconnected && nReason != 0) {
                            Log.w(TAG, "Connection failed for battery poll: " + (name != null ? name : mac) + ", reason: " + nReason);
                            scheduleRetryWithBackoff(mac, name);
                        }
                    }
                });
            } else {
                // 이미 연결됨, 바로 배터리 읽기
                readBatteryLevel(mac, name, beacon);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error polling battery for " + mac + ": " + e.getMessage());
            scheduleRetryWithBackoff(mac, name);
        }
    }
    
    /**
     * 배터리 레벨 읽기 (내부 메서드)
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     * @param beacon KBeacon 인스턴스
     */
    private void readBatteryLevel(String mac, String name, KBeacon beacon) {
        try {
            // KBeacon의 getBatteryPercent() 직접 사용 (광고 패킷에서 취득)
            int batteryPercent = beacon.getBatteryPercent();
            
            if (batteryPercent > 0) {
                Log.d(TAG, "Battery read successful: " + (name != null ? name : mac) + " = " + batteryPercent + "%");
                
                // BeaconDataStore 업데이트
                if (mDataStore != null) {
                    var beaconState = mDataStore.get(mac);
                    if (beaconState != null) {
                        beaconState.setBatteryPercent(batteryPercent);
                        mDataStore.upsert(beaconState);
                    }
                }
                
                // SharedPreferences에 저장 (저장 훅 호출)
                if (mActivity != null) {
                    mActivity.saveBatteryPct(mac, name, batteryPercent);
                }
                
                // 성공 시 백오프 초기화 및 다음 폴링 시간 설정
                mBackoffSeconds.remove(mac);
                long nextPollTime = System.currentTimeMillis() + POLL_INTERVAL_MS;
                mNextPollEpochMs.put(mac, nextPollTime);
                
                // 다음 정기 폴링 스케줄
                scheduleNextRegularPoll(mac, name);
                
            } else {
                Log.w(TAG, "Invalid battery level received: " + batteryPercent + ", retrying later");
                scheduleRetryWithBackoff(mac, name);
            }
            
            // TODO: 연결 유지/해제 정책은 현재 앱 패턴에 맞춰 최소 변경
            // 필요하면 여기서 disconnect() 호출
            
        } catch (Exception e) {
            Log.e(TAG, "Error reading battery for " + mac + ": " + e.getMessage());
            scheduleRetryWithBackoff(mac, name);
        }
    }
    
    /**
     * 백오프로 재시도 스케줄 (내부 메서드)
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     */
    private void scheduleRetryWithBackoff(String mac, String name) {
        // 지수 백오프 계산
        Integer currentBackoff = mBackoffSeconds.get(mac);
        int backoffSec;
        
        if (currentBackoff == null) {
            backoffSec = BACKOFF_MIN_SEC;
        } else {
            backoffSec = Math.min(currentBackoff * 2, BACKOFF_MAX_SEC);
        }
        
        mBackoffSeconds.put(mac, backoffSec);
        
        Log.d(TAG, "Retrying battery poll in " + backoffSec + "s for: " + (name != null ? name : mac));
        
        // 다음 폴링 시간 업데이트
        long nextPollTime = System.currentTimeMillis() + (backoffSec * 1000L);
        mNextPollEpochMs.put(mac, nextPollTime);
        
        // 재시도 스케줄
        ScheduledFuture<?> task = mExecutor.schedule(
            () -> pollBattery(mac, name), 
            backoffSec, 
            TimeUnit.SECONDS
        );
        
        mScheduledTasks.put(mac, task);
    }
    
    /**
     * 다음 정기 폴링 스케줄 (내부 메서드)
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     */
    private void scheduleNextRegularPoll(String mac, String name) {
        // 1시간 후 정기 폴링 스케줄
        ScheduledFuture<?> task = mExecutor.schedule(
            () -> pollBattery(mac, name), 
            POLL_INTERVAL_MS, 
            TimeUnit.MILLISECONDS
        );
        
        mScheduledTasks.put(mac, task);
        
        Log.d(TAG, "Scheduled next battery poll in 1h for: " + (name != null ? name : mac));
    }
    
    /**
     * 특정 비콘의 다음 배터리 폴링 시간 조회
     * @param mac 비콘 MAC 주소
     * @return 다음 폴링 시간 (epoch ms, null이면 스케줄 없음)
     */
    public Long getNextPollTime(String mac) {
        return mNextPollEpochMs.get(mac);
    }
    
    /**
     * 현재 스케줄된 작업 수
     * @return 활성 작업 수
     */
    public int getActiveTaskCount() {
        return mScheduledTasks.size();
    }
    
    /**
     * 특정 비콘의 배터리 조회 강제 실행
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     */
    public void forcePoll(String mac, String name) {
        Log.d(TAG, "Force polling battery for " + (name != null ? name : mac));
        
        // 기존 작업 취소
        ScheduledFuture<?> existingTask = mScheduledTasks.get(mac);
        if (existingTask != null) {
            existingTask.cancel(false);
        }
        
        // 즉시 실행
        mExecutor.submit(() -> pollBattery(mac, name));
    }
    
    /**
     * BatteryScheduler 정리 (앱 종료 시 호출)
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down BatteryScheduler");
        
        // 모든 작업 취소
        for (ScheduledFuture<?> task : mScheduledTasks.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        
        mScheduledTasks.clear();
        mNextPollEpochMs.clear();
        mBackoffSeconds.clear();
        
        if (mExecutor != null) {
            mExecutor.shutdownNow();
        }
    }
}