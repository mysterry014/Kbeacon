package com.kkmcn.sensordemo.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor;
import com.kkmcn.kbeaconlib2.KBException;
import com.kkmcn.kbeaconlib2.KBeacon;
import com.kkmcn.kbeaconlib2.KBeaconsMgr;
import com.kkmcn.kbeaconlib2.KBConnState;
import com.kkmcn.sensordemo.R;
import com.kkmcn.sensordemo.model.BeaconState;
import com.kkmcn.sensordemo.utils.RssiWindow;
import com.kkmcn.sensordemo.data.ServicePrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * KBeacon BLE 관리 Foreground Service
 * 
 * 주요 기능:
 * - Foreground Service로 안정적 백그라운드 BLE 스캔
 * - MAC 기반 샘플 수집 게이트 (이름 필터는 표시 전용)
 * - RSSI/거리 필터링 및 EMA 처리
 * - 배터리 정보 광고 수집
 * - 연결 기반 Ring 명령 송신
 * - 자동 알람 거리 초과 감지
 */
public class BleService extends Service implements KBeaconsMgr.KBeaconMgrDelegate {
    
    private static final String TAG = "BleService";
    
    // Notification 관련 상수
    private static final String CHANNEL_ID = "BLE_SERVICE_CHANNEL";
    private static final int NOTIFICATION_ID = 1001;
    
    // Broadcast Action 상수
    public static final String ACTION_BEACON_UPDATE = "com.kkmcn.sensordemo.BEACON_UPDATE";
    public static final String ACTION_SCAN_STATE_CHANGED = "com.kkmcn.sensordemo.SCAN_STATE_CHANGED";
    public static final String ACTION_RING_STATE_CHANGED = "com.kkmcn.sensordemo.RING_STATE_CHANGED";
    public static final String ACTION_AUTO_ALARM_TRIGGERED = "com.kkmcn.sensordemo.AUTO_ALARM_TRIGGERED";
    
    // 필터링 상수
    private static final Pattern NAME_FILTER_PATTERN = Pattern.compile("^\\d{6}_.+");
    private static final int RSSI_WINDOW_SIZE = 10;
    private static final double RSSI_OUTLIER_THRESHOLD = 7.0;
    private static final double RSSI_EMA_ALPHA = 0.25;
    private static final double DISTANCE_EMA_ALPHA = 0.30;
    
    // 타이밍 상수 (ms)
    private static final long BEACON_UPDATE_INTERVAL = 500;
    private static final long BATTERY_UPDATE_INTERVAL = 60 * 60 * 1000; // 1시간
    private static final long RING_RETRY_INTERVAL = 15000; // 15초
    
    // 기본 캘리브레이션 값
    private static final double DEFAULT_TX_POWER_AT_1M = -59.0;
    private static final double DEFAULT_PATH_LOSS_EXPONENT = 2.0;
    
    // Service 바인더
    public class BleServiceBinder extends Binder {
        public BleService getService() {
            return BleService.this;
        }
    }
    private final IBinder binder = new BleServiceBinder();
    
    // BLE 관리
    private KBeaconsMgr kBeaconsMgr;
    private volatile boolean isScanning = false;
    
    // 데이터 저장소
    private final ConcurrentHashMap<String, BeaconState> beaconStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RssiWindow> rssiWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> rssiEmaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> distanceEmaCache = new ConcurrentHashMap<>();
    
    // MAC 기반 수집 게이트 - 선택된 MAC만 데이터 수집
    private final ConcurrentHashMap<String, String> selectedMacToName = new ConcurrentHashMap<>();
    
    // Ring 관리
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeRingTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> ringInProgress = new ConcurrentHashMap<>();
    
    // 스케줄러
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> beaconUpdateTask;
    private ScheduledFuture<?> batteryUpdateTask;
    
    // UI 업데이트 핸들러
    private Handler mainHandler;
    
    // 영속 저장소
    private ServicePrefs servicePrefs;
    
    // 자동 알람 활성화 상태
    private volatile boolean autoAlarmEnabled = true;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BleService onCreate");
        
        // 핸들러 초기화
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 스케줄러 초기화
        scheduler = Executors.newScheduledThreadPool(4);
        
        // 영속 저장소 초기화
        servicePrefs = new ServicePrefs(this);
        
        // 저장된 MAC 게이트 레지스트리 복원
        restoreSavedData();
        
        // Notification 채널 생성
        createNotificationChannel();
        
        // Foreground Service 시작
        startForeground(NOTIFICATION_ID, createNotification());
        
        // KBeaconsMgr 초기화 (올바른 API 사용)
        try {
            kBeaconsMgr = KBeaconsMgr.sharedBeaconManager(this);
            if (kBeaconsMgr != null) {
                kBeaconsMgr.delegate = this;
                kBeaconsMgr.setScanMode(KBeaconsMgr.SCAN_MODE_LOW_LATENCY);
                Log.d(TAG, "KBeaconsMgr initialized successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize KBeaconsMgr", e);
        }
        
        // 주기적 업데이트 시작
        startPeriodicUpdates();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BleService onStartCommand");
        
        // 자동 스캔 시작 (CLAUDE.md 요구사항)
        if (!isScanning) {
            startScanning();
        }
        
        return START_STICKY; // 서비스 재시작 허용
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "BleService onBind");
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "BleService onDestroy");
        
        // 스캔 중지
        stopScanning();
        
        // 모든 Ring 작업 중지
        stopAllRingAlarms();
        
        // 스케줄러 종료
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        
        // KBeaconsMgr 정리
        if (kBeaconsMgr != null) {
            kBeaconsMgr.delegate = null;
        }
        
        super.onDestroy();
    }
    
    /**
     * Notification 채널 생성 (Android 8+)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "KBeacon BLE 서비스",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("BLE 비콘 스캔 및 거리 모니터링");
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * Foreground Service Notification 생성
     */
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KBeacon 모니터링 활성")
            .setContentText("BLE 비콘 거리 모니터링 중...")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    /**
     * 주기적 업데이트 작업 시작
     */
    private void startPeriodicUpdates() {
        // 500ms 주기 비콘 상태 업데이트
        beaconUpdateTask = scheduler.scheduleWithFixedDelay(
            this::broadcastBeaconUpdate,
            BEACON_UPDATE_INTERVAL,
            BEACON_UPDATE_INTERVAL,
            TimeUnit.MILLISECONDS
        );
        
        // 1시간 주기 배터리 업데이트
        batteryUpdateTask = scheduler.scheduleWithFixedDelay(
            this::updateAllBatteryLevels,
            BATTERY_UPDATE_INTERVAL,
            BATTERY_UPDATE_INTERVAL,
            TimeUnit.MILLISECONDS
        );
    }
    
    // ========== Public API ==========
    
    /**
     * BLE 스캔 시작
     */
    public boolean startScanning() {
        Log.d(TAG, "startScanning called");
        
        if (kBeaconsMgr == null) {
            Log.e(TAG, "KBeaconsMgr not initialized");
            return false;
        }
        
        if (isScanning) {
            Log.w(TAG, "Already scanning");
            return true;
        }
        
        try {
            // LOW_LATENCY 모드로 스캔 시작 (사용자 분석 결과 반영)
            int result = kBeaconsMgr.startScanning();
            if (result == 0) { // 성공
                isScanning = true;
                Log.i(TAG, "BLE scan started successfully");
                broadcastScanStateChanged(true);
                return true;
            } else {
                Log.e(TAG, "Failed to start BLE scan, error: " + result);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during BLE scan start", e);
            return false;
        }
    }
    
    /**
     * BLE 스캔 중지
     */
    public boolean stopScanning() {
        Log.d(TAG, "stopScanning called");
        
        if (kBeaconsMgr == null) {
            return true;
        }
        
        if (!isScanning) {
            Log.w(TAG, "Not currently scanning");
            return true;
        }
        
        try {
            kBeaconsMgr.stopScanning();
            isScanning = false;
            Log.i(TAG, "BLE scan stopped");
            broadcastScanStateChanged(false);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception during BLE scan stop", e);
            return false;
        }
    }
    
    /**
     * 스캔 상태 확인
     */
    public boolean isScanningActive() {
        return isScanning;
    }
    
    /**
     * 현재 비콘 상태 목록 반환 (이름 필터 적용)
     */
    public List<BeaconState> getFilteredBeaconStates() {
        List<BeaconState> filtered = new ArrayList<>();
        
        for (BeaconState state : beaconStates.values()) {
            // 표시용 이름 필터: 6자리 숫자로 시작하는 이름만
            String displayName = state.getDisplayName();
            if (displayName != null && NAME_FILTER_PATTERN.matcher(displayName).matches()) {
                filtered.add(state);
            }
        }
        
        // MAC 주소 기준 정렬 (일관된 순서)
        Collections.sort(filtered, (a, b) -> {
            String macA = a.getMac() != null ? a.getMac() : "";
            String macB = b.getMac() != null ? b.getMac() : "";
            return macA.compareToIgnoreCase(macB);
        });
        
        return filtered;
    }
    
    /**
     * MAC 기반 수집 게이트에 비콘 등록
     * 이름 필터를 통과한 비콘의 MAC을 등록하여 데이터 수집 허용
     */
    public void registerMacForCollection(String mac, String displayName) {
        if (mac != null && displayName != null) {
            selectedMacToName.put(mac, displayName);
            Log.d(TAG, String.format("MAC registered for collection: %s -> %s", mac, displayName));
        }
    }
    
    /**
     * MAC 기반 수집 게이트에서 비콘 해제
     */
    public void unregisterMacForCollection(String mac) {
        if (mac != null) {
            String oldName = selectedMacToName.remove(mac);
            Log.d(TAG, String.format("MAC unregistered from collection: %s (was: %s)", mac, oldName));
        }
    }
    
    /**
     * Ring 알람 시작 (특정 MAC)
     */
    public void startRingAlarm(String mac) {
        if (mac == null) {
            Log.w(TAG, "startRingAlarm: MAC is null");
            return;
        }
        
        Log.d(TAG, "startRingAlarm: " + mac);
        
        // 이미 실행 중인지 확인
        if (activeRingTasks.containsKey(mac)) {
            Log.w(TAG, "Ring alarm already active for MAC: " + mac);
            return;
        }
        
        // Ring 상태 설정
        ringInProgress.put(mac, true);
        broadcastRingStateChanged(mac, "동작중");
        
        // 반복 Ring 작업 스케줄링
        ScheduledFuture<?> ringTask = scheduler.scheduleWithFixedDelay(
            () -> performRingCommand(mac),
            0, // 즉시 시작
            RING_RETRY_INTERVAL,
            TimeUnit.MILLISECONDS
        );
        
        activeRingTasks.put(mac, ringTask);
    }
    
    /**
     * Ring 알람 중지 (특정 MAC)
     */
    public void stopRingAlarm(String mac) {
        if (mac == null) {
            Log.w(TAG, "stopRingAlarm: MAC is null");
            return;
        }
        
        Log.d(TAG, "stopRingAlarm: " + mac);
        
        // Ring 작업 취소
        ScheduledFuture<?> task = activeRingTasks.remove(mac);
        if (task != null) {
            task.cancel(true);
        }
        
        // Ring 상태 해제
        ringInProgress.remove(mac);
        broadcastRingStateChanged(mac, "알람");
    }
    
    /**
     * 모든 Ring 알람 중지
     */
    public void stopAllRingAlarms() {
        Log.d(TAG, "stopAllRingAlarms");
        
        for (String mac : new ArrayList<>(activeRingTasks.keySet())) {
            stopRingAlarm(mac);
        }
    }
    
    /**
     * 자동 알람 활성화/비활성화
     */
    public void setAutoAlarmEnabled(boolean enabled) {
        this.autoAlarmEnabled = enabled;
        Log.d(TAG, "Auto alarm enabled: " + enabled);
    }
    
    /**
     * 자동 알람 활성화 상태 확인
     */
    public boolean isAutoAlarmEnabled() {
        return autoAlarmEnabled;
    }
    
    // ========== KBeaconsMgr.KBeaconMgrDelegate ==========
    
    @Override
    public void onBeaconDiscovered(KBeacon[] kBeacons) {
        if (kBeacons == null || kBeacons.length == 0) {
            return;
        }
        
        for (KBeacon beacon : kBeacons) {
            if (beacon != null) {
                processBeaconAdvertisement(beacon);
            }
        }
    }
    
    @Override
    public void onScanFailed(int errorCode) {
        Log.e(TAG, "BLE scan failed with error: " + errorCode);
        isScanning = false;
        broadcastScanStateChanged(false);
    }
    
    @Override
    public void onCentralBleStateChang(int nNewState) {
        Log.i(TAG, "Central BLE state changed: " + nNewState);
    }
    
    // ========== Private Methods ==========
    
    /**
     * 비콘 광고 처리 (핵심 로직)
     */
    private void processBeaconAdvertisement(KBeacon beacon) {
        String mac = beacon.getMac();
        if (mac == null) {
            return;
        }
        
        // BeaconState 조회/생성
        BeaconState state = beaconStates.computeIfAbsent(mac, k -> {
            BeaconState newState = new BeaconState(mac);
            rssiWindows.put(mac, new RssiWindow(RSSI_WINDOW_SIZE, RSSI_OUTLIER_THRESHOLD));
            Log.d(TAG, "New beacon discovered: " + mac);
            return newState;
        });
        
        // 광고 이름 업데이트 (있는 경우)
        String advName = beacon.getName();
        if (advName != null && !advName.isEmpty()) {
            state.setAdvertisedName(advName);
            
            // 이름 필터 통과 시 MAC 수집 게이트에 자동 등록
            if (NAME_FILTER_PATTERN.matcher(advName).matches()) {
                if (!selectedMacToName.containsKey(mac)) {
                    registerMacForCollectionWithSave(mac, advName);
                }
            }
        }
        
        // MAC 수집 게이트 확인: 등록된 MAC만 RSSI/거리 데이터 수집
        if (!selectedMacToName.containsKey(mac)) {
            // 수집 게이트 통과 실패: 광고 이름만 업데이트하고 RSSI/거리는 수집하지 않음
            return;
        }
        
        // RSSI 처리
        int currentRssi = beacon.getRssi();
        state.setLastRssi(currentRssi);
        
        // RSSI 윈도우 업데이트
        RssiWindow rssiWindow = rssiWindows.get(mac);
        if (rssiWindow != null) {
            rssiWindow.addSample(currentRssi);
            
            // 이상치 제거 후 EMA 적용
            List<Integer> filteredSamples = rssiWindow.getFilteredSamples();
            if (!filteredSamples.isEmpty()) {
                double avgFiltered = filteredSamples.stream().mapToInt(Integer::intValue).average().orElse(0.0);
                
                // RSSI EMA 적용
                Double prevRssiEma = rssiEmaCache.get(mac);
                double rssiFiltered = prevRssiEma == null ? 
                    avgFiltered : 
                    RSSI_EMA_ALPHA * avgFiltered + (1 - RSSI_EMA_ALPHA) * prevRssiEma;
                
                rssiEmaCache.put(mac, rssiFiltered);
                state.setRssiFiltered(rssiFiltered);
                
                // 거리 계산 (저장된 캘리브레이션 값 사용)
                double distance = calculateDistanceWithSavedCalibration(mac, rssiFiltered);
                
                // 거리 EMA 적용
                Double prevDistanceEma = distanceEmaCache.get(mac);
                double distanceFiltered = prevDistanceEma == null ?
                    distance :
                    DISTANCE_EMA_ALPHA * distance + (1 - DISTANCE_EMA_ALPHA) * prevDistanceEma;
                
                distanceEmaCache.put(mac, distanceFiltered);
                state.setDistanceFiltered(distanceFiltered);
                
                // 자동 알람 거리 초과 감지
                checkAutoAlarmTrigger(mac, state, distanceFiltered);
            }
        }
        
        // KSensor 패킷에서 배터리 정보 추출 (TODO: 실제 구현 필요)
        // extractBatteryFromSensorPacket(beacon, state);
        
        // 타임스탬프 업데이트
        state.setLastUpdateTime(System.currentTimeMillis());
    }
    
    /**
     * KSensor 패킷에서 배터리 정보 추출 (TODO: 실제 API 확인 후 구현)
     */
    private void extractBatteryFromSensorPacket(KBeacon beacon, BeaconState state) {
        try {
            // TODO: KBeacon API 확인 후 실제 구현
            // 현재는 임시로 주석 처리
            /*
            if (beacon.getAdvPackets() != null) {
                for (int i = 0; i < beacon.getAdvPackets().size(); i++) {
                    if (beacon.getAdvPackets().get(i) instanceof KBAdvPacketSensor) {
                        KBAdvPacketSensor sensorPacket = (KBAdvPacketSensor) beacon.getAdvPackets().get(i);
                        
                        // 배터리 전압 → % 변환 (sensordemo 로직 재사용)
                        if (sensorPacket.getBatteryLevel() != null) {
                            float voltage = sensorPacket.getBatteryLevel();
                            int batteryPercent = calculateBatteryPercent(voltage);
                            state.setBatteryPercent(batteryPercent);
                            state.setBatteryVoltage(voltage);
                            
                            Log.v(TAG, String.format("Battery updated from adv: MAC=%s, voltage=%.2fV, percent=%d%%", 
                                state.getMac(), voltage, batteryPercent));
                        }
                        break;
                    }
                }
            }
            */
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract battery from sensor packet: " + e.getMessage());
        }
    }
    
    /**
     * 거리 계산 (캘리브레이션 값 적용)
     */
    private double calculateDistance(String mac, double rssiFiltered) {
        // TODO: SharedPreferences에서 캘리브레이션 값 로드
        double txPowerAt1m = DEFAULT_TX_POWER_AT_1M;
        double pathLossExponent = DEFAULT_PATH_LOSS_EXPONENT;
        
        // 거리 공식: distance = 10^((txPowerAt1m - rssi)/(10 * n))
        double distance = Math.pow(10, (txPowerAt1m - rssiFiltered) / (10 * pathLossExponent));
        
        // 범위 제한 (0.1m ~ 99.9m)
        return Math.max(0.1, Math.min(99.9, distance));
    }
    
    /**
     * 배터리 전압 → % 변환 (sensordemo 로직)
     */
    private int calculateBatteryPercent(float voltage) {
        // KBeacon 배터리 특성 곡선 (sensordemo 참조)
        if (voltage >= 3.0f) {
            return 100;
        } else if (voltage >= 2.9f) {
            return (int) ((voltage - 2.9f) / 0.1f * 20 + 80);
        } else if (voltage >= 2.7f) {
            return (int) ((voltage - 2.7f) / 0.2f * 60 + 20);
        } else {
            return Math.max(0, (int) ((voltage - 2.3f) / 0.4f * 20));
        }
    }
    
    /**
     * 자동 알람 거리 초과 감지
     */
    private void checkAutoAlarmTrigger(String mac, BeaconState state, double distanceFiltered) {
        if (!autoAlarmEnabled) {
            return;
        }
        
        // 이미 Ring이 활성화된 경우 중복 알람 방지
        if (ringInProgress.containsKey(mac)) {
            return;
        }
        
        // 거리 설정값 확인
        double thresholdDistance = state.getDistanceThreshold();
        if (thresholdDistance <= 0) {
            return; // 설정값 없음
        }
        
        // 거리 초과 감지
        if (distanceFiltered > thresholdDistance) {
            Log.w(TAG, String.format("Auto alarm triggered: MAC=%s, distance=%.1fm > threshold=%.1fm", 
                mac, distanceFiltered, thresholdDistance));
            
            // 비콘 부저 알람 시작
            startRingAlarm(mac);
            
            // 태블릿 알람 브로드캐스트
            Intent intent = new Intent(ACTION_AUTO_ALARM_TRIGGERED);
            intent.putExtra("mac", mac);
            intent.putExtra("distance", distanceFiltered);
            intent.putExtra("threshold", thresholdDistance);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }
    
    /**
     * Ring 명령 실행 (연결 → 명령 → 연결 해제)
     */
    private void performRingCommand(String mac) {
        Log.d(TAG, "performRingCommand: " + mac);
        
        if (!ringInProgress.containsKey(mac)) {
            Log.w(TAG, "Ring command called but not in progress for MAC: " + mac);
            return;
        }
        
        try {
            // KBeacon 인스턴스 찾기
            KBeacon beacon = findBeaconByMac(mac);
            if (beacon == null) {
                Log.e(TAG, "Beacon not found for MAC: " + mac);
                broadcastRingStateChanged(mac, "알람"); // 실패시 기본 상태로
                return;
            }
            
            // 연결 상태 확인 후 연결 또는 바로 명령 전송
            if (beacon.getState() != KBConnState.Connected) {
                Log.d(TAG, "Connecting to beacon: " + mac);
                broadcastRingStateChanged(mac, "연결됨");
                
                // 패스워드를 사용한 인증된 연결 (기본 패스워드)
                beacon.connect("0000000000000000", 20000, new KBeacon.ConnStateDelegate() {
                    @Override
                    public void onConnStateChange(KBeacon beacon, KBConnState state, int nReason) {
                        Log.i(TAG, "Connection state changed: " + state + ", reason: " + nReason);
                        if (state == KBConnState.Connected) {
                            Log.i(TAG, "Connected successfully, sending ring command");
                            sendRingJson(beacon, mac, 3000); // 3초 부저
                        } else if (state == KBConnState.Disconnected && nReason != 0) {
                            Log.e(TAG, "Connection failed, reason: " + nReason);
                            broadcastRingStateChanged(mac, "알람"); // 실패시 기본 상태로
                        }
                    }
                });
            } else {
                // 이미 연결됨, 바로 명령 전송
                Log.i(TAG, "Beacon already connected, sending ring command directly");
                sendRingJson(beacon, mac, 3000);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing ring command for " + mac + ": " + e.getMessage());
            broadcastRingStateChanged(mac, "알람"); // 실패시 기본 상태로
        }
    }
    
    /**
     * 실제 Ring JSON 명령 전송
     */
    private void sendRingJson(KBeacon beacon, String mac, int ringMs) {
        try {
            org.json.JSONObject cmd = new org.json.JSONObject();
            cmd.put("msg", "ring");
            cmd.put("ringTime", ringMs); // ms
            cmd.put("ringType", 0x1);    // 0x1=beep only (부저)
            
            Log.i(TAG, "Sending ring command: ringTime=" + ringMs + "ms, ringType=0x1");
            
            beacon.sendCommand(cmd, new KBeacon.ActionCallback() {
                @Override
                public void onActionComplete(boolean bConfigSuccess, KBException error) {
                    if (bConfigSuccess) {
                        Log.i(TAG, "Ring command sent successfully for MAC: " + mac);
                        broadcastRingStateChanged(mac, "알람중");
                    } else {
                        Log.e(TAG, "Ring command failed: " + (error != null ? error.errorCode : "unknown"));
                        broadcastRingStateChanged(mac, "알람"); // 실패시 기본 상태로
                    }
                    
                    // 연결 해제 (명령 완료 후)
                    if (beacon.getState() == KBConnState.Connected) {
                        beacon.disconnect();
                        Log.d(TAG, "Disconnected after ring command");
                    }
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "sendRingJson error: " + e.getMessage());
            broadcastRingStateChanged(mac, "알람");
        }
    }
    
    /**
     * MAC 주소로 KBeacon 인스턴스 찾기
     * RingManager 패턴을 따라 KBeaconsMgr.getBeacon() 사용
     */
    private KBeacon findBeaconByMac(String mac) {
        if (kBeaconsMgr == null || mac == null) {
            Log.w(TAG, "findBeaconByMac: kBeaconsMgr or mac is null");
            return null;
        }
        
        try {
            // KBeaconsMgr.getBeacon()으로 MAC 기반 KBeacon 인스턴스 조회
            KBeacon beacon = kBeaconsMgr.getBeacon(mac);
            if (beacon != null) {
                Log.d(TAG, String.format("Found beacon: %s, name=%s, state=%s", 
                    mac, beacon.getName(), beacon.getState()));
            } else {
                Log.w(TAG, "Beacon not found in KBeaconsMgr for MAC: " + mac);
            }
            return beacon;
        } catch (Exception e) {
            Log.e(TAG, "Error finding beacon by MAC " + mac + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 전체 비콘 배터리 레벨 업데이트 (1시간 주기)
     */
    private void updateAllBatteryLevels() {
        Log.d(TAG, "updateAllBatteryLevels: periodic battery check");
        
        for (BeaconState state : beaconStates.values()) {
            String mac = state.getMac();
            
            // 등록된 MAC만 배터리 업데이트
            if (selectedMacToName.containsKey(mac)) {
                // 마지막 배터리 업데이트로부터 1시간 경과 확인
                long lastUpdate = state.getLastBatteryUpdateTime();
                long now = System.currentTimeMillis();
                
                if (now - lastUpdate >= BATTERY_UPDATE_INTERVAL) {
                    // TODO: 비동기 배터리 조회 구현
                    Log.d(TAG, "Scheduling battery update for MAC: " + mac);
                }
            }
        }
    }
    
    /**
     * 비콘 상태 업데이트 브로드캐스트 (500ms 주기)
     */
    private void broadcastBeaconUpdate() {
        Intent intent = new Intent(ACTION_BEACON_UPDATE);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    /**
     * 스캔 상태 변경 브로드캐스트
     */
    private void broadcastScanStateChanged(boolean scanning) {
        Intent intent = new Intent(ACTION_SCAN_STATE_CHANGED);
        intent.putExtra("scanning", scanning);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    /**
     * Ring 상태 변경 브로드캐스트
     */
    private void broadcastRingStateChanged(String mac, String state) {
        Intent intent = new Intent(ACTION_RING_STATE_CHANGED);
        intent.putExtra("mac", mac);
        intent.putExtra("state", state);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    // ========== 영속 저장소 관련 메서드들 ==========
    
    /**
     * 저장된 데이터 복원 (서비스 시작 시)
     */
    private void restoreSavedData() {
        Log.d(TAG, "Restoring saved data...");
        
        try {
            // MAC 게이트 레지스트리 복원
            Map<String, String> savedMacs = servicePrefs.getRegisteredMacs();
            for (Map.Entry<String, String> entry : savedMacs.entrySet()) {
                selectedMacToName.put(entry.getKey(), entry.getValue());
            }
            Log.i(TAG, String.format("Restored %d MAC entries from registry", savedMacs.size()));
            
            // 저장된 BeaconState 복원 (거리 설정값, 배터리 정보, 별칭)
            for (String mac : savedMacs.keySet()) {
                BeaconState state = beaconStates.computeIfAbsent(mac, k -> {
                    BeaconState newState = new BeaconState(mac);
                    rssiWindows.put(mac, new RssiWindow(RSSI_WINDOW_SIZE, RSSI_OUTLIER_THRESHOLD));
                    return newState;
                });
                
                // 거리 설정값 복원
                double threshold = servicePrefs.getDistanceThreshold(mac);
                state.setDistanceThreshold(threshold);
                
                // 배터리 정보 복원
                ServicePrefs.BatteryInfo batteryInfo = servicePrefs.getBatteryInfo(mac);
                if (batteryInfo != null) {
                    state.setBatteryPercent(batteryInfo.percent);
                    state.setBatteryVoltage(batteryInfo.voltage);
                    state.setLastBatteryUpdateTime(batteryInfo.updateTime);
                }
                
                // 별칭 복원
                String alias = servicePrefs.getDeviceAlias(mac);
                if (alias != null) {
                    state.setAlias(alias);
                }
                
                Log.v(TAG, String.format("Restored state for %s: threshold=%.1f, battery=%d%%, alias=%s", 
                    mac, threshold, batteryInfo != null ? batteryInfo.percent : 0, alias));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore saved data", e);
        }
    }
    
    /**
     * 거리 계산 (캘리브레이션 값 적용)
     * ServicePrefs에서 저장된 캘리브레이션 결과를 우선 사용
     */
    private double calculateDistanceWithSavedCalibration(String mac, double rssiFiltered) {
        double txPowerAt1m = DEFAULT_TX_POWER_AT_1M;
        double pathLossExponent = DEFAULT_PATH_LOSS_EXPONENT;
        
        // ServicePrefs에서 캘리브레이션 결과 로드
        if (servicePrefs != null) {
            ServicePrefs.CalibrationResult calibration = servicePrefs.getCalibrationResult(mac);
            if (calibration != null) {
                txPowerAt1m = calibration.txPowerAt1m;
                pathLossExponent = calibration.pathLossExponent;
                Log.v(TAG, String.format("Using saved calibration for %s: txPower=%.1f, n=%.2f", 
                    mac, txPowerAt1m, pathLossExponent));
            }
        }
        
        // 거리 공식: distance = 10^((txPowerAt1m - rssi)/(10 * n))
        double distance = Math.pow(10, (txPowerAt1m - rssiFiltered) / (10 * pathLossExponent));
        
        // 범위 제한 (0.1m ~ 99.9m)
        return Math.max(0.1, Math.min(99.9, distance));
    }
    
    /**
     * 캘리브레이션 결과 저장 (외부 호출용)
     */
    public void saveCalibrationResult(String mac, double txPowerAt1m, double pathLossExponent, 
                                    double rSquared, double rmse) {
        if (servicePrefs != null) {
            servicePrefs.saveCalibrationResult(mac, txPowerAt1m, pathLossExponent, rSquared, rmse);
            Log.i(TAG, String.format("Calibration result saved for %s", mac));
        }
    }
    
    /**
     * 거리 설정값 저장
     */
    public void saveDistanceThreshold(String mac, double threshold) {
        if (servicePrefs != null) {
            servicePrefs.saveDistanceThreshold(mac, threshold);
            
            // BeaconState도 동시 업데이트
            BeaconState state = beaconStates.get(mac);
            if (state != null) {
                state.setDistanceThreshold(threshold);
            }
        }
    }
    
    /**
     * 장치 별칭 저장
     */
    public void saveDeviceAlias(String mac, String alias) {
        if (servicePrefs != null) {
            servicePrefs.saveDeviceAlias(mac, alias);
            
            // BeaconState도 동시 업데이트
            BeaconState state = beaconStates.get(mac);
            if (state != null) {
                state.setAlias(alias);
            }
        }
    }
    
    /**
     * MAC 수집 게이트에 등록 (영속 저장 포함)
     */
    public void registerMacForCollectionWithSave(String mac, String displayName) {
        if (mac != null && displayName != null) {
            selectedMacToName.put(mac, displayName);
            
            // ServicePrefs에도 저장
            if (servicePrefs != null) {
                servicePrefs.registerMacForCollection(mac, displayName);
            }
            
            Log.d(TAG, String.format("MAC registered for collection: %s -> %s", mac, displayName));
        }
    }
    
    /**
     * MAC 수집 게이트에서 해제 (영속 저장 포함)
     */
    public void unregisterMacForCollectionWithSave(String mac) {
        if (mac != null) {
            String oldName = selectedMacToName.remove(mac);
            
            // ServicePrefs에서도 제거
            if (servicePrefs != null) {
                servicePrefs.unregisterMacForCollection(mac);
            }
            
            Log.d(TAG, String.format("MAC unregistered from collection: %s (was: %s)", mac, oldName));
        }
    }
}