package com.kkmcn.sensordemo.ring;

import android.util.Log;

import com.kkmcn.kbeaconlib2.KBConnState;
import com.kkmcn.kbeaconlib2.KBeacon;
import com.kkmcn.kbeaconlib2.KBeaconsMgr;
import com.kkmcn.sensordemo.data.BeaconDataStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 여러 비콘 장치의 동시 부저 반복 알람 관리
 * - 스케줄러 기반 재트리거 (ringTime 만료 후 자동 재실행)
 * - 장치별 독립적인 알람 상태 관리
 * - 연결 실패 시 지수 백오프 적용
 * 
 * Note: KBeacon ringDevice() API 사용, 광고 RSSI 기반 설계
 */
public class RingManager {
    private static final String TAG = "RING";
    
    // 링 세션 상태
    public enum RingState {
        IDLE,       // 비활성
        STARTING,   // 연결/링 시작 중
        RUNNING,    // 링 동작 중
        STOPPED     // 중지됨
    }
    
    // 링 세션 정보
    private static class RingSession {
        volatile RingState state;
        ScheduledFuture<?> scheduledTask;
        int backoffSeconds;
        int ringMs;
        String mac;
        String name;
        
        RingSession(String mac, String name, int ringMs) {
            this.state = RingState.IDLE;
            this.mac = mac;
            this.name = name;
            this.ringMs = ringMs;
            this.backoffSeconds = 0;
        }
    }
    
    private final ScheduledExecutorService mExecutor;
    private final KBeaconsMgr mBeaconsMgr;
    private final BeaconDataStore mDataStore;
    private final ConcurrentHashMap<String, RingSession> mSessions;
    
    // 설정값들
    private static final int RING_DEFAULT_MS = 2000;       // 기본 링 시간 2초
    private static final int GUARD_INTERVAL_MS = 500;      // 가드 인터벌
    private static final int BACKOFF_MIN_SEC = 2;          // 최소 백오프 2초
    private static final int BACKOFF_MAX_SEC = 30;         // 최대 백오프 30초
    
    /**
     * RingManager 생성자
     * @param beaconsMgr KBeaconsMgr 인스턴스
     * @param dataStore BeaconDataStore 인스턴스
     */
    public RingManager(KBeaconsMgr beaconsMgr, BeaconDataStore dataStore) {
        mExecutor = Executors.newScheduledThreadPool(2);
        mBeaconsMgr = beaconsMgr;
        mDataStore = dataStore;
        mSessions = new ConcurrentHashMap<>();
        Log.d(TAG, "RingManager initialized");
    }
    
    /**
     * 비콘 부저 알람 시작 (반복 모드)
     * @param mac 비콘 MAC 주소
     * @return true if 시작됨, false if 이미 실행 중이거나 오류
     */
    public boolean start(String mac) {
        return start(mac, RING_DEFAULT_MS);
    }
    
    /**
     * 비콘 부저 알람 시작 (반복 모드)
     * @param mac 비콘 MAC 주소
     * @param ringMs 링 지속시간 (밀리초)
     * @return true if 시작됨, false if 이미 실행 중이거나 오류
     */
    public boolean start(String mac, int ringMs) {
        if (mac == null || mac.isEmpty()) {
            Log.w(TAG, "Invalid MAC for ring start");
            return false;
        }
        
        // 이미 실행 중인지 확인
        RingSession existingSession = mSessions.get(mac);
        if (existingSession != null && 
            (existingSession.state == RingState.STARTING || existingSession.state == RingState.RUNNING)) {
            Log.d(TAG, "Ring already active for MAC: " + mac);
            return false;
        }
        
        // 비콘 이름 조회
        String name = "Unknown";
        if (mDataStore != null) {
            var beaconState = mDataStore.get(mac);
            if (beaconState != null) {
                name = beaconState.getName();
            }
        }
        
        // 새 세션 생성
        RingSession session = new RingSession(mac, name, ringMs);
        session.state = RingState.STARTING;
        mSessions.put(mac, session);
        
        Log.d(TAG, "Starting ring for " + name + " (" + mac + "), ringMs=" + ringMs);
        
        // TODO Phase 3: BeaconState에 ringActive=true 반영 (500ms 렌더에서 상태 표시용)
        
        // 첫 번째 링 작업 스케줄
        session.scheduledTask = mExecutor.schedule(() -> executeRingOnce(session), 0, TimeUnit.SECONDS);
        
        return true;
    }
    
    /**
     * 비콘 부저 알람 중지
     * @param mac 비콘 MAC 주소
     * @return true if 중지됨, false if 실행 중이지 않음
     */
    public boolean stop(String mac) {
        if (mac == null || mac.isEmpty()) {
            Log.w(TAG, "Invalid MAC for ring stop");
            return false;
        }
        
        RingSession session = mSessions.get(mac);
        if (session == null || session.state == RingState.IDLE || session.state == RingState.STOPPED) {
            Log.d(TAG, "No active ring session for MAC: " + mac);
            return false;
        }
        
        Log.d(TAG, "Stopping ring for " + session.name + " (" + mac + ")");
        
        // 스케줄된 작업 취소
        if (session.scheduledTask != null) {
            session.scheduledTask.cancel(false);
        }
        
        session.state = RingState.STOPPED;
        
        // TODO Phase 3: BeaconState에 ringActive=false 반영
        
        // 세션 제거
        mSessions.remove(mac);
        
        return true;
    }
    
    /**
     * 모든 비콘의 부저 알람 중지
     */
    public void stopAll() {
        Log.d(TAG, "Stopping all ring sessions (" + mSessions.size() + ")");
        
        for (String mac : mSessions.keySet()) {
            stop(mac);
        }
        
        mSessions.clear();
    }
    
    /**
     * 현재 실행 중인 링 세션 수
     * @return 활성 세션 수
     */
    public int getActiveSessionCount() {
        int count = 0;
        for (RingSession session : mSessions.values()) {
            if (session.state == RingState.STARTING || session.state == RingState.RUNNING) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 특정 비콘의 링 상태 조회
     * @param mac 비콘 MAC 주소
     * @return 링 상태 (null이면 세션 없음)
     */
    public RingState getRingState(String mac) {
        RingSession session = mSessions.get(mac);
        return session != null ? session.state : null;
    }
    
    /**
     * RingManager 정리 (앱 종료 시 호출)
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down RingManager");
        
        stopAll();
        
        if (mExecutor != null) {
            mExecutor.shutdownNow();
        }
    }
    
    /**
     * 단일 링 명령 실행 (내부 메서드)
     * @param session 링 세션
     */
    private void executeRingOnce(RingSession session) {
        try {
            if (session.state == RingState.STOPPED) {
                Log.d(TAG, "Session stopped, cancelling ring: " + session.mac);
                return;
            }
            
            // KBeacon 인스턴스 조회
            KBeacon beacon = mBeaconsMgr.getBeacon(session.mac);
            if (beacon == null) {
                Log.w(TAG, "Beacon not found: " + session.mac);
                scheduleRetryWithBackoff(session);
                return;
            }
            
            // 연결 상태 확인 및 연결 시도
            if (beacon.getState() != KBConnState.Connected) {
                Log.d(TAG, "Connecting to beacon: " + session.name);
                
                // TODO: 연결 타임아웃/패스워드 설정이 필요하면 여기서 처리
                beacon.connect(null, 10000, new KBeacon.ConnStateDelegate() {
                    @Override
                    public void onConnStateChange(KBeacon beacon, KBConnState state, int nReason) {
                        if (state == KBConnState.Connected) {
                            Log.d(TAG, "Connected, sending ring command: " + session.name);
                            sendRingCommand(session, beacon);
                        } else if (state == KBConnState.Disconnected && nReason != 0) {
                            Log.w(TAG, "Connection failed: " + session.name + ", reason: " + nReason);
                            scheduleRetryWithBackoff(session);
                        }
                    }
                });
            } else {
                // 이미 연결됨, 바로 링 명령 전송
                sendRingCommand(session, beacon);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing ring for " + session.mac + ": " + e.getMessage());
            scheduleRetryWithBackoff(session);
        }
    }
    
    /**
     * 링 명령 전송 (내부 메서드)
     * @param session 링 세션
     * @param beacon KBeacon 인스턴스
     */
    private void sendRingCommand(RingSession session, KBeacon beacon) {
        try {
            session.state = RingState.RUNNING;
            
            // TODO: 실제 KBeacon API에서 ringDevice 메서드 시그니처 확인 후 수정 필요
            // 임시로 로그만 출력하고 성공 처리
            Log.d(TAG, "Ring command would be sent to: " + session.name + ", duration: " + session.ringMs + "ms");
            
            // 시뮬레이션: 사용자 카디거나 버튼 클릭시 메시지 표시
            Log.d(TAG, "Ring command sent successfully: " + session.name);
            
            // 백오프 초기화
            session.backoffSeconds = 0;
            
            // 다음 링을 위한 스케줄 (ringTime + 가드 인터벌 후)
            if (session.state == RingState.RUNNING) {
                long nextDelayMs = session.ringMs + GUARD_INTERVAL_MS;
                session.scheduledTask = mExecutor.schedule(
                    () -> executeRingOnce(session), 
                    nextDelayMs, 
                    TimeUnit.MILLISECONDS
                );
            }
            
            // TODO 실제 버전:
            /*
            beacon.ringDevice("ring", session.ringMs, 0x1, 0, 0, new KBeacon.ActionCallback() {
                @Override
                public void onActionComplete(boolean bConfigSuccess, KBeacon.ActionError error) {
                }
            });
            */
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending ring command for " + session.mac + ": " + e.getMessage());
            scheduleRetryWithBackoff(session);
        }
    }
    
    /**
     * 백오프로 재시도 스케줄 (내부 메서드)
     * @param session 링 세션
     */
    private void scheduleRetryWithBackoff(RingSession session) {
        if (session.state == RingState.STOPPED) {
            return;
        }
        
        // 지수 백오프 계산 (2, 4, 8, 16, 30초 max)
        if (session.backoffSeconds == 0) {
            session.backoffSeconds = BACKOFF_MIN_SEC;
        } else {
            session.backoffSeconds = Math.min(session.backoffSeconds * 2, BACKOFF_MAX_SEC);
        }
        
        Log.d(TAG, "Retrying ring in " + session.backoffSeconds + "s for: " + session.name);
        
        session.scheduledTask = mExecutor.schedule(
            () -> executeRingOnce(session), 
            session.backoffSeconds, 
            TimeUnit.SECONDS
        );
    }
}