package com.kkmcn.sensordemo.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
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
import java.util.Set;
import java.util.HashSet;

import com.kkmcn.sensordemo.prefs.DevicePrefs;
import com.kkmcn.sensordemo.data.Prefs;

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
    
    // Command Gate 패턴용 Ring 호출 이유 추적
    public enum RingReason {
        USER_TAP_ON, USER_TAP_OFF, AUTO_ON, AUTO_OFF, WATCHDOG, PURGE_OFFLINE, OTHER
    }
    
    // Broadcast Action 상수
    public static final String ACTION_BEACON_UPDATE = "com.kkmcn.sensordemo.BEACON_UPDATE";
    public static final String ACTION_SCAN_STATE_CHANGED = "com.kkmcn.sensordemo.SCAN_STATE_CHANGED";
    public static final String ACTION_RING_STATE_CHANGED = "com.kkmcn.sensordemo.RING_STATE_CHANGED";
    public static final String ACTION_AUTO_ALARM_TRIGGERED = "com.kkmcn.sensordemo.AUTO_ALARM_TRIGGERED";
    public static final String ACTION_SCAN_NO_RESULTS = "com.kkmcn.sensordemo.SCAN_NO_RESULTS";
    public static final String ACTION_TOAST = "com.kkmcn.sensordemo.ACTION_TOAST";
    
    // 스캔 결과 감시
    private volatile long lastAdvTs = 0L;
    private static final long NO_RESULT_TIMEOUT_MS = 7000; // 7초
    
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
    
    // TTL 관련 상수 (Issue 4)
    private static final long BEACON_TTL_MS = 30000; // 30초 후 오프라인 비콘 제거
    
    // Watchdog 관련 상수
    private static final long WATCHDOG_PERIOD_MS = 5 * 60 * 1000L;  // 5분마다 체크
    private static final long NO_ADV_RESTART_MS = 10 * 60 * 1000L; // 10분 광고 0이면 재시작
    private static final long PERIODIC_SCAN_RESTART_MS = 30 * 60 * 1000L; // 30분마다 예방적 재시작
    
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
    
    // 하이브리드 스캔 구조: paired(추적 대상) + candidates(후보)
    private final ConcurrentHashMap<String, BeaconState> candidates = new ConcurrentHashMap<>();
    private Set<String> pairedSet = new HashSet<>();
    private static final Pattern NAME_REGEX = Pattern.compile("^\\d{6}_.+");
    
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
    
    // Watchdog 관리
    private long lastAdvAt = System.currentTimeMillis();
    private long lastScanRestartAt = System.currentTimeMillis();
    private Handler wdHandler = new Handler(Looper.getMainLooper());
    
    
    // 자동 알람 활성화 상태
    private volatile boolean autoAlarmEnabled = true;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BleService onCreate");
        
        // 크래시 방지: 5초 룰 방지를 위해 즉시 startForeground 호출
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        Log.d(TAG, "Foreground service started immediately");
        
        // 핸들러 초기화
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 스케줄러 초기화
        scheduler = Executors.newScheduledThreadPool(4);
        
        // 영속 저장소 초기화
        servicePrefs = new ServicePrefs(this);
        
        // 저장된 paired MAC 목록 복원
        pairedSet = DevicePrefs.getPaired(getApplicationContext());
        Log.d(TAG, "Restored paired devices: " + pairedSet.size());
        
        // 저장된 MAC 게이트 레지스트리 복원
        restoreSavedData();
        
        // KBeaconsMgr 초기화 (권한 체크 없이)
        try {
            kBeaconsMgr = KBeaconsMgr.sharedBeaconManager(this);
            if (kBeaconsMgr != null) {
                kBeaconsMgr.delegate = this;
                // 스캔 모드 설정은 실제 스캔 시작 시로 지연
                Log.e(TAG, "FORCE LOG: KBeaconsMgr initialized successfully, delegate set to BleService");
                Log.e(TAG, "FORCE LOG: Current delegate: " + kBeaconsMgr.delegate);
                Log.e(TAG, "FORCE LOG: Delegate class: " + (kBeaconsMgr.delegate != null ? kBeaconsMgr.delegate.getClass().getSimpleName() : "null"));
            } else {
                Log.e(TAG, "FORCE LOG: KBeaconsMgr initialization failed - null returned");
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
        
        // 알림에서 "중지" 액션 클릭 시 서비스 종료
        if (intent != null && "ACTION_STOP_FGS".equals(intent.getAction())) {
            Log.d(TAG, "Stop foreground service requested from notification");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // 크래시 스나이퍼 패치: ③ 3중 게이트 적용
        ensureScanning();
        startScanWatchdog(); // Watchdog 시작
        
        return START_STICKY; // 서비스 재시작 허용
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "BleService onBind");
        return binder;
    }
    
    // 첫 번째 onDestroy 제거 (중복 방지) - 두 번째 onDestroy가 모든 기능 포함
    
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
        // 알림 클릭 시 앱으로 복귀하는 Intent
        Intent openApp = new Intent(this, com.kkmcn.sensordemo.DeviceScanActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent openPendingIntent = android.app.PendingIntent.getActivity(
            this, 1001, openApp, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        // 서비스 중지 Intent (선택사항)
        Intent stopService = new Intent(this, BleService.class).setAction("ACTION_STOP_FGS");
        android.app.PendingIntent stopPendingIntent = android.app.PendingIntent.getService(
            this, 1002, stopService, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KBeacon 모니터링 활성")
            .setContentText("BLE 비콘 거리 모니터링 중...")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)  // 알림 클릭 시 앱 복귀
            .addAction(new NotificationCompat.Action(0, "중지", stopPendingIntent))  // 중지 액션
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
     * BLE 스캔 시작 (권한 게이트 적용)
     */
    public boolean startScanning() {
        Log.d(TAG, "startScanning called");
        
        // 크래시 방지: 권한 없으면 스캔 금지
        if (!hasBluetoothScanPermission()) {
            Log.w(TAG, "No BLUETOOTH_SCAN permission, sending permission request broadcast");
            Intent intent = new Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            return false;
        }
        
        if (kBeaconsMgr == null) {
            Log.e(TAG, "KBeaconsMgr not initialized");
            return false;
        }
        
        if (isScanning) {
            Log.w(TAG, "Already scanning");
            return true;
        }
        
        try {
            // 스캔 모드 설정 (이제 권한이 있으므로 안전)
            kBeaconsMgr.setScanMode(KBeaconsMgr.SCAN_MODE_LOW_LATENCY);
            
            // LOW_LATENCY 모드로 스캔 시작
            Log.e(TAG, "FORCE LOG: About to start BLE scanning...");
            int result = kBeaconsMgr.startScanning();
            Log.e(TAG, "FORCE LOG: startScanning() returned: " + result);
            if (result == 0) { // 성공
                isScanning = true;
                Log.e(TAG, "FORCE LOG: BLE scan started successfully, waiting for onBeaconDiscovered callbacks...");
                broadcastScanStateChanged(true);

                // 워치독: NO_RESULT_TIMEOUT_MS 안에 광고 없으면 폴백 브로드캐스트
                mainHandler.postDelayed(() -> {
                    if (isScanning && (System.currentTimeMillis() - lastAdvTs) > NO_RESULT_TIMEOUT_MS) {
                        // 상황 로그
                        Log.w(TAG, "No scan results within timeout. Broadcasting fallback request.");
                        // 위치 설정 상태도 같이 담아주면 액티비티에서 분기 가능
                        boolean locationEnabled = isLocationEnabled();
                        Intent i = new Intent(ACTION_SCAN_NO_RESULTS);
                        i.putExtra("location_enabled", locationEnabled);
                        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
                    }
                }, NO_RESULT_TIMEOUT_MS);

                return true;
            } else {
                Log.e(TAG, "FORCE LOG: Failed to start BLE scan, error code: " + result);
                // ★ 추가: 실패 시에도 폴백 브로드캐스트 발송
                boolean locationEnabled = isLocationEnabled();
                Intent i = new Intent(ACTION_SCAN_NO_RESULTS);
                i.putExtra("location_enabled", locationEnabled);
                LocalBroadcastManager.getInstance(this).sendBroadcast(i);
                return false;
            }
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException during BLE scan start: " + se.getMessage());
            Intent intent = new Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            return false;
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
     * 스캔 상태 확인 (바인더 API)
     */
    public boolean isScanningActive() {
        return isScanning;
    }
    
    /**
     * 하이브리드 스캔: 모든 비콘 상태 반환 (필터 제거)
     * UI에서 paired/candidate 분기 처리
     */
    public List<BeaconState> getFilteredBeaconStates() {
        // Issue 4: 스테일 비콘 정리 (UI 업데이트 전에 실행)
        removeStaleBeacons();
        
        List<BeaconState> filtered = new ArrayList<>();
        for (BeaconState state : beaconStates.values()) {
            String mac = state.getMac();
            String name = state.getAdvertisedName();
            
            // 이름이 6자리 숫자로 시작하거나, 이전에 paired로 저장된 MAC이면 표시
            boolean nameMatches = name != null && NAME_REGEX.matcher(name.trim()).matches();
            boolean isPaired = pairedSet.contains(mac);
            
            if (nameMatches || isPaired) {
                filtered.add(state);
            }
        }
        
        // MAC 기반 안정 정렬 (앱 재실행 시에도 동일한 순서 유지)
        Collections.sort(filtered, (a, b) -> {
            String macA = a.getMac();
            String macB = b.getMac();
            if (macA == null && macB == null) return 0;
            if (macA == null) return 1;
            if (macB == null) return -1;
            return macA.compareToIgnoreCase(macB);
        });
        
        return filtered;
    }
    
    /**
     * Issue 4: TTL 기반 스테일(오프라인) 비콘 정리
     * 마지막 업데이트로부터 BEACON_TTL_MS 이상 경과된 비콘을 제거
     */
    private void removeStaleBeacons() {
        long currentTime = System.currentTimeMillis();
        List<String> staleBeacons = new ArrayList<>();
        
        for (Map.Entry<String, BeaconState> entry : beaconStates.entrySet()) {
            String mac = entry.getKey();
            BeaconState state = entry.getValue();
            
            if (currentTime - state.getUpdatedAt() > BEACON_TTL_MS) {
                staleBeacons.add(mac);
                Log.i(TAG, String.format("Removing stale beacon: MAC=%s, name=%s, offline=%.1fs", 
                    mac, state.getDisplayName(), (currentTime - state.getUpdatedAt()) / 1000.0));
            }
        }
        
        // 스테일 비콘 제거
        for (String staleMac : staleBeacons) {
            beaconStates.remove(staleMac);
            
            // 연관 데이터도 정리
            rssiEmaCache.remove(staleMac);
            distanceEmaCache.remove(staleMac);
            
            // Ring 상태도 정리 (오프라인 비콘의 phantom ring 방지)
            ringInProgress.remove(staleMac);
            
            Log.d(TAG, "Cleaned up stale beacon data: " + staleMac);
        }
        
        if (!staleBeacons.isEmpty()) {
            Log.i(TAG, String.format("Removed %d stale beacons", staleBeacons.size()));
        }
    }
    
    /**
     * 후보 비콘 목록 반환 (이름 필터 통과한 신규 비콘)
     */
    public List<BeaconState> getCandidateBeaconStates() {
        return new ArrayList<>(candidates.values());
    }
    
    /**
     * MAC을 paired 목록에 추가 (바인더 API)
     */
    public void addPaired(String mac) {
        if (!pairedSet.contains(mac)) {
            pairedSet.add(mac);
            DevicePrefs.addPaired(getApplicationContext(), mac);
            Log.d(TAG, "Added to paired: " + mac);
            
            // candidates에서 제거 (이미 paired로 승격)
            candidates.remove(mac);
        }
    }
    
    /**
     * 20개 제한을 위해 가장 오래된 paired 비콘 제거 (LRU 방식)
     */
    private void removeOldestPaired() {
        String oldestMac = null;
        long oldestTime = Long.MAX_VALUE;
        
        // BeaconState의 lastUpdateTime을 기준으로 가장 오래된 것 찾기
        for (String mac : pairedSet) {
            BeaconState state = beaconStates.get(mac);
            if (state != null) {
                long lastUpdate = state.getLastUpdateTime();
                if (lastUpdate < oldestTime) {
                    oldestTime = lastUpdate;
                    oldestMac = mac;
                }
            }
        }
        
        if (oldestMac != null) {
            pairedSet.remove(oldestMac);
            beaconStates.remove(oldestMac);
            rssiWindows.remove(oldestMac);
            rssiEmaCache.remove(oldestMac);
            distanceEmaCache.remove(oldestMac);
            DevicePrefs.removePaired(getApplicationContext(), oldestMac);
            Log.d(TAG, "Removed oldest paired beacon: " + oldestMac);
        }
    }
    
    /**
     * MAC을 paired 목록에서 제거 (바인더 API)
     */
    public void removePaired(String mac) {
        if (pairedSet.contains(mac)) {
            pairedSet.remove(mac);
            DevicePrefs.removePaired(getApplicationContext(), mac);
            Log.d(TAG, "Removed from paired: " + mac);
        }
    }
    
    /**
     * 하이브리드 스캔: MAC을 paired 목록에 등록 (레거시 호환)
     * @deprecated 대신 addPaired(String mac) 사용
     */
    @Deprecated
    public void registerMacForCollection(String mac, String displayName) {
        if (mac != null) {
            addPaired(mac);
            Log.d(TAG, String.format("MAC registered for collection (deprecated): %s -> %s", mac, displayName));
        }
    }
    
    /**
     * 하이브리드 스캔: MAC을 paired 목록에서 제거 (레거시 호환)
     * @deprecated 대신 removePaired(String mac) 사용
     */
    @Deprecated
    public void unregisterMacForCollection(String mac) {
        if (mac != null) {
            removePaired(mac);
            Log.d(TAG, String.format("MAC unregistered from collection (deprecated): %s", mac));
        }
    }
    
    /**
     * Ring 알람 시작 (하위 호환용 - @Deprecated)
     */
    @Deprecated
    public void startRingAlarm(String mac) {
        Log.d(TAG, "startRingAlarm (deprecated): " + mac);
        setDesiredRingPublic(mac, true, RingReason.OTHER);
    }
    
    /**
     * Ring 알람 중지 (하위 호환용 - @Deprecated)
     */
    @Deprecated
    public void stopRingAlarm(String mac) {
        Log.d(TAG, "stopRingAlarm (deprecated): " + mac);
        setDesiredRingPublic(mac, false, RingReason.USER_TAP_OFF);
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
        lastAdvTs = System.currentTimeMillis();
        Log.e(TAG, "FORCE LOG: onBeaconDiscovered called with " + (kBeacons != null ? kBeacons.length : 0) + " beacons");
        if (kBeacons == null || kBeacons.length == 0) {
            return;
        }
        
        for (KBeacon beacon : kBeacons) {
            if (beacon != null) {
                String mac = beacon.getMac();
                String name = beacon.getName();
                int rssi = beacon.getRssi();
                Log.e(TAG, "FORCE LOG: Processing beacon MAC=" + mac + " name=" + name + " rssi=" + rssi);
                processBeaconAdvertisement(beacon);
            }
        }
    }
    
    /**
     * 라이브러리 버전 호환성을 위한 브릿지 메서드
     * ArrayList<KBeacon> 시그니처를 사용하는 버전 대응
     */
    public void onBeaconDiscovered(java.util.ArrayList<KBeacon> list) {
        lastAdvTs = System.currentTimeMillis();
        Log.e(TAG, "FORCE LOG: onBeaconDiscovered(ArrayList) called with " + (list != null ? list.size() : 0) + " beacons");
        if (list == null || list.isEmpty()) {
            return;
        }
        
        for (KBeacon beacon : list) {
            if (beacon != null) {
                String mac = beacon.getMac();
                String name = beacon.getName();
                int rssi = beacon.getRssi();
                Log.e(TAG, "FORCE LOG: Processing beacon (ArrayList ver) MAC=" + mac + " name=" + name + " rssi=" + rssi);
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
     * 하이브리드 스캔: 비콘 광고 처리 (핵심 로직)
     * 1) 모든 MAC을 beaconStates에 저장
     * 2) paired MAC은 전체 처리 + UI 표시
     * 3) 이름 필터 통과한 candidate는 candidates 컬렉션에 저장
     */
    private void processBeaconAdvertisement(KBeacon beacon) {
        String mac = beacon.getMac();
        if (mac == null) {
            return;
        }
        
        // BeaconState 조회/생성 (모든 MAC에 대해)
        BeaconState state = beaconStates.computeIfAbsent(mac, k -> {
            BeaconState newState = new BeaconState(mac);
            rssiWindows.put(mac, new RssiWindow(RSSI_WINDOW_SIZE, RSSI_OUTLIER_THRESHOLD));
            
            // DevicePrefs에서 저장된 값들 복원
            newState.setDistanceThreshold(DevicePrefs.getDistanceThreshold(getApplicationContext(), mac, 50.0f));
            int savedBattery = DevicePrefs.getBattery(getApplicationContext(), mac, -1);
            if (savedBattery >= 0) {
                newState.setBatteryPercent(savedBattery);
            }
            
            Log.d(TAG, "New beacon discovered: " + mac + 
                     ", distance threshold: " + newState.getDistanceThreshold() + 
                     ", saved battery: " + savedBattery);
            return newState;
        });
        
        // 온라인 타임스탬프 갱신 (isOnline 판정 근거)
        state.setUpdatedAt(System.currentTimeMillis());
        
        // Watchdog용 광고 수신 타임스탬프 갱신
        onAnyAdvertisementObserved();

        // 광고 이름 업데이트 (있는 경우)
        String advName = beacon.getName();
        if (advName != null && !advName.isEmpty()) {
            state.setAdvertisedName(advName);
        }
        
        // 기본 RSSI 업데이트 (모든 비콘에 대해)
        int currentRssi = beacon.getRssi();
        state.setLastRssi(currentRssi);
        state.setLastUpdateTime(System.currentTimeMillis());
        
        // 디버깅 로그: 하이브리드 스캔 상태 (FORCE LOG)
        if (advName != null && NAME_REGEX.matcher(advName).matches()) {
            Log.e(TAG, "FORCE LOG: Name filter PASS: " + advName + " (MAC: " + mac + ")");
        } else if (advName != null) {
            Log.e(TAG, "FORCE LOG: Name filter FAIL: " + advName + " (MAC: " + mac + ")");
        }
        
        Log.e(TAG, String.format("FORCE LOG: Beacon %s -> Paired: %s, Name: %s, RSSI: %d, " +
                                 "Paired count: %d, Candidates: %d", 
                                 mac, pairedSet.contains(mac), advName, currentRssi,
                                 pairedSet.size(), candidates.size()));
        
        // 이름 필터 통과 시 자동으로 paired에 추가 (최대 20개 유지)
        boolean nameMatches = advName != null && NAME_REGEX.matcher(advName).matches();
        if (nameMatches && !pairedSet.contains(mac)) {
            // 20개 제한 확인
            if (pairedSet.size() >= 20) {
                removeOldestPaired();
            }
            addPaired(mac);
            Log.d(TAG, "Auto-paired new beacon: " + mac + " (" + advName + ")");
        }
        
        // 하이브리드 분기 처리
        if (pairedSet.contains(mac)) {
            // Paired 비콘: 전체 처리 (RSSI 필터링, 거리 계산, 자동 알람)
            processPairedBeacon(beacon, state, currentRssi);
            publishPairedToUI(state);
        } else {
            // 이름 미일치인 잡음은 후보에만 유지
            if (nameMatches) {
                candidates.put(mac, state);
                publishCandidateToUI(state);
            }
        }
    }
    
    /**
     * Paired 비콘 전체 처리 (RSSI 필터링, 거리 계산, 자동 알람)
     */
    private void processPairedBeacon(KBeacon beacon, BeaconState state, int currentRssi) {
        String mac = beacon.getMac();
        
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
                
                // 거리 계산 (DevicePrefs에서 캘리브레이션 값 사용)
                double distance = calculateDistanceWithDevicePrefs(mac, rssiFiltered);
                
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
        
        // KSensor/시스템/TLM 광고 패킷에서 배터리 정보 추출
        extractBatteryFromAdvPackets(beacon, state);
        
        // Paired 비콘 UI 업데이트
        publishPairedToUI(state);
    }
    
    /**
     * Prefs를 사용한 거리 계산 (캘리브레이션 결과 반영)
     */
    private double calculateDistanceWithDevicePrefs(String mac, double rssiFiltered) {
        // BeaconState에서 이름 정보 가져오기
        BeaconState state = beaconStates.get(mac);
        String name = (state != null) ? state.getName() : null;
        
        // Prefs 인스턴스 생성하여 캘리브레이션 값 로드
        Prefs prefs = new Prefs(getApplicationContext());
        double txPowerAt1m = prefs.getTxPowerAt1m(mac, name, DEFAULT_TX_POWER_AT_1M);
        double pathLossN = prefs.getN(mac, name, DEFAULT_PATH_LOSS_EXPONENT);
        
        // distance(m) = 10^((txPowerAt1m - rssiFiltered)/(10 * n))
        double distance = Math.pow(10, (txPowerAt1m - rssiFiltered) / (10.0 * pathLossN));
        
        // [Issue 3 Debug] 문제의 비콘에 대한 거리 계산 상세 로깅
        if (mac != null && mac.toLowerCase().contains("561976")) {
            Log.e(TAG, String.format("[561976_DISTANCE] MAC=%s, name=%s, rssi=%.1f, txPower=%.2f, n=%.2f, distance=%.3fm", 
                mac, name, rssiFiltered, txPowerAt1m, pathLossN, distance));
        }
        
        return distance;
    }
    
    /**
     * Paired 비콘 UI 업데이트 브로드캐스트
     */
    private void publishPairedToUI(BeaconState state) {
        Intent intent = new Intent(ACTION_BEACON_UPDATE);
        // 필요하면 최소 정보 putExtra, 아니면 신호만
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    /**
     * Candidate 비콘 UI 업데이트 브로드캐스트 (선택사항)
     */
    private void publishCandidateToUI(BeaconState state) {
        // 필요시 구현 (후보 비콘 표시용)
        // Intent intent = new Intent(ACTION_CANDIDATE_UPDATE);
        // intent.putExtra("candidate_beacon", state);
        // LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    /**
     * 광고 패킷에서 배터리 정보 추출 및 저장
     */
    private void extractBatteryFromAdvPackets(KBeacon beacon, BeaconState state) {
        try {
            if (beacon.allAdvPackets() == null) return;

            Integer batteryPercent = null;
            Float batteryVoltage = null;

            // 광고 패킷 순회하여 배터리 정보 추출
            for (com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketBase pkt : beacon.allAdvPackets()) {
                
                // 1) System 패킷: 퍼센트 바로 제공 (가장 신뢰도 높음)
                if (pkt instanceof com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSystem) {
                    com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSystem sys = 
                        (com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSystem) pkt;
                    int pct = sys.getBatteryPercent();
                    Log.i(TAG, String.format("DEBUG Battery %s: sys=%d, model=%s, version=%s", 
                        state.getMac(), pct, sys.getModel(), sys.getVersion()));
                    
                    if (pct >= 0 && pct <= 100) {
                        batteryPercent = pct;
                        Log.i(TAG, "Battery ACCEPTED from System: " + state.getMac() + " = " + pct + "%");
                        break; // 가장 신뢰도 높은 경로 → 바로 채택
                    } else {
                        Log.w(TAG, "Battery REJECTED from System: " + state.getMac() + " pct=" + pct);
                    }
                }

                // 2) Sensor 패킷: 전압(V) 제공 → 퍼센트 환산
                if (pkt instanceof com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor) {
                    com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor sensor = 
                        (com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor) pkt;
                    Integer vInt = sensor.getBatteryLevel();
                    if (vInt != null && vInt > 0) {
                        batteryVoltage = vInt.floatValue() / 1000f; // mV → V 변환
                        Log.i(TAG, String.format("DEBUG Battery %s: sensorV=%.3f", state.getMac(), batteryVoltage));
                        // System 패킷이 같이 있으면 그걸 우선하므로 계속 탐색
                    }
                }

                // 3) Eddystone TLM: 전압 제공 → 퍼센트 환산
                if (pkt instanceof com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyTLM) {
                    com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyTLM tlm = 
                        (com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyTLM) pkt;
                    Integer vInt = tlm.getBatteryLevel();
                    if (vInt != null && vInt > 0) {
                        // TLM이 mV 단위로 오므로 V로 변환
                        batteryVoltage = vInt.floatValue() / 1000f; // mV → V 변환
                        Log.i(TAG, String.format("DEBUG Battery %s: tlmV=%.3f", state.getMac(), batteryVoltage));
                    }
                }
            }

            // 전압 → 퍼센트 변환 (System 패킷이 없는 경우)
            if (batteryPercent == null && batteryVoltage != null) {
                batteryPercent = calculateBatteryPercent(batteryVoltage);
                if (batteryPercent != null) {
                    state.setBatteryVoltage(batteryVoltage);
                    Log.v(TAG, "Converted voltage to percent: " + batteryVoltage + "V → " + batteryPercent + "%");
                }
            }

            // 배터리 정보 저장 (상태 + 영속 저장)
            if (batteryPercent != null && batteryPercent >= 0 && batteryPercent <= 100) {
                state.setBatteryPercent(batteryPercent);
                state.setLastBatteryUpdateTime(System.currentTimeMillis());
                DevicePrefs.setBattery(getApplicationContext(), state.getMac(), batteryPercent);
                Log.i(TAG, "Battery FINAL updated from ADV: " + state.getMac() + " = " + batteryPercent + "%");
            } else {
                Log.w(TAG, String.format("Battery FINAL skipped from ADV: MAC=%s, percent=%s (invalid/null)", 
                    state.getMac(), batteryPercent));
            }

            // System 패킷에서 0%인 경우 Scan Response 0x8020 Service Data 체크 시도
            if (batteryPercent != null && batteryPercent == 0) {
                Log.i(TAG, "Attempting Scan Response 0x8020 check for " + state.getMac());
                checkScanResponseBattery(beacon, state);
            }

        } catch (Throwable t) {
            Log.w(TAG, "extractBatteryFromAdvPackets error for " + state.getMac() + ": " + t.getMessage());
        }
    }

    /**
     * 배터리 레벨을 BeaconState와 DevicePrefs에 동시 저장
     */
    public void saveBatteryLevel(String mac, int batteryPercent) {
        // BeaconState 업데이트
        BeaconState state = beaconStates.get(mac);
        if (state != null) {
            state.setBatteryPercent(batteryPercent);
            state.setLastBatteryUpdateTime(System.currentTimeMillis());
        }
        
        // DevicePrefs에 영속 저장
        DevicePrefs.setBattery(getApplicationContext(), mac, batteryPercent);
        
        Log.d(TAG, String.format("Battery level saved: %s -> %d%%", mac, batteryPercent));
    }
    
    /**
     * 거리 임계값을 BeaconState와 DevicePrefs에 동시 저장
     */
    public void saveDistanceThreshold(String mac, float thresholdMeters) {
        // BeaconState 업데이트
        BeaconState state = beaconStates.get(mac);
        if (state != null) {
            state.setDistanceThreshold(thresholdMeters);
        }
        
        // DevicePrefs에 영속 저장
        DevicePrefs.setDistanceThreshold(getApplicationContext(), mac, thresholdMeters);
        
        Log.d(TAG, String.format("Distance threshold saved: %s -> %.1fm", mac, thresholdMeters));
    }
    
    /**
     * 거리 임계값 조회 (BeaconState 우선, 없으면 DevicePrefs에서)
     */
    public float getDistanceThreshold(String mac) {
        BeaconState state = beaconStates.get(mac);
        if (state != null && state.getDistanceThresholdMeters() > 0) {
            return (float)state.getDistanceThresholdMeters();
        }
        return DevicePrefs.getDistanceThreshold(getApplicationContext(), mac, 50.0f);
    }
    
    /**
     * 캘리브레이션 결과를 DevicePrefs에 저장 (프롬프트 4-A용)
     */
    public void saveCalibrationResult(String mac, float txPowerAt1m, float pathLossN) {
        // DevicePrefs에 저장
        DevicePrefs.setCalibration(getApplicationContext(), mac, txPowerAt1m, pathLossN);
        
        Log.d(TAG, String.format("Calibration saved: %s -> tx1m=%.2f, n=%.2f", 
                                 mac, txPowerAt1m, pathLossN));
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
     * Scan Response 0x8020 Service Data 체크 (현재 SDK에서 미지원)
     * 추후 SDK 업데이트 시 구현 예정
     */
    private void checkScanResponseBattery(KBeacon beacon, BeaconState state) {
        Log.d(TAG, "checkScanResponseBattery: SDK에서 getScanRecord() 미지원 - 스킵");
        Log.d(TAG, "System/Sensor/TLM 패킷에서 배터리 정보를 우선 활용하세요");
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
        
        // [Issue 3 Debug] 문제의 비콘에 대한 상세 로깅
        String beaconName = state.getDisplayName();
        boolean is561976Beacon = mac != null && (mac.toLowerCase().contains("561976") || 
            (beaconName != null && beaconName.contains("561976")));
        
        if (is561976Beacon) {
            Log.e(TAG, String.format("[561976_DEBUG] Auto-alarm check: MAC=%s, name=%s, distance=%.3fm, threshold=%.1fm, rssi=%.1fdBm, txPower=%.1f, n=%.2f", 
                mac, beaconName, distanceFiltered, thresholdDistance, 
                state.getRssiFiltered(), state.getTxPowerAt1m(), state.getPathLossExponent()));
        }
        
        // [Issue 3 Safeguard] 의심스러운 거리 계산 감지 및 차단
        double rssiFiltered = state.getRssiFiltered();
        if (rssiFiltered > -30.0 && distanceFiltered > 10.0) {
            // 매우 강한 신호(-30dBm 이상)인데 거리가 10m 이상으로 계산된 경우
            Log.w(TAG, String.format("Suspicious distance calculation blocked: MAC=%s, rssi=%.1f, distance=%.1f", 
                mac, rssiFiltered, distanceFiltered));
            return; // 자동 알람 차단
        }
        
        // 거리 초과 감지
        if (distanceFiltered > thresholdDistance) {
            Log.w(TAG, String.format("Auto alarm triggered: MAC=%s, name=%s, distance=%.1fm > threshold=%.1fm", 
                mac, beaconName, distanceFiltered, thresholdDistance));
            
            // 비콘 부저 알람 시작 (Command Gate 패턴 사용)
            setDesiredRingPublic(mac, true, RingReason.AUTO_ON);
            
            // 태블릿 알람 브로드캐스트
            Intent intent = new Intent(ACTION_AUTO_ALARM_TRIGGERED);
            intent.putExtra("mac", mac);
            intent.putExtra("distance", distanceFiltered);
            intent.putExtra("threshold", thresholdDistance);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }
    
    /**
     * Ring 명령 실행 - 크래시 스나이퍼 패치 적용
     */
    private void performRingCommand(String mac) {
        Log.d(TAG, "performRingCommand: " + mac);
        
        // 크래시 스나이퍼 패치: 3중 게이트 (권한 + BT ON)
        if (!hasAllBlePerms() || !isBtOn()) {
            Log.w(TAG, String.format("Ring prerequisites not met for %s: perms=%s, btOn=%s", 
                mac, hasAllBlePerms(), isBtOn()));
            Intent intent = new Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            broadcastRingStateChanged(mac, "알람");
            return;
        }
        
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
            
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException in ring command for " + mac + ": " + se.getMessage());
            Intent intent = new Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            broadcastRingStateChanged(mac, "알람");
        } catch (Exception e) {
            Log.e(TAG, "Error executing ring command for " + mac + ": " + e.getMessage());
            broadcastRingStateChanged(mac, "알람"); // 실패시 기본 상태로
        }
    }
    
    /**
     * Ring 알람 중지 명령을 비콘에 직접 송신
     */
    private void stopBeaconRing(String mac) {
        Log.d(TAG, "stopBeaconRing: " + mac);
        
        // 크래시 스나이퍼 패치: 권한 및 BT 상태 확인
        if (!hasAllBlePerms() || !isBtOn()) {
            Log.w(TAG, String.format("Stop ring prerequisites not met for %s: perms=%s, btOn=%s", 
                mac, hasAllBlePerms(), isBtOn()));
            return;
        }
        
        try {
            KBeacon beacon = findBeaconByMac(mac);
            if (beacon == null) {
                Log.e(TAG, "Beacon not found for stop ring MAC: " + mac);
                return;
            }
            
            // 연결 상태 확인 후 연결 또는 바로 명령 전송
            if (beacon.getState() != KBConnState.Connected) {
                Log.d(TAG, "Connecting to beacon for stop command: " + mac);
                
                // 패스워드를 사용한 인증된 연결 (기본 패스워드)
                beacon.connect("0000000000000000", 20000, new KBeacon.ConnStateDelegate() {
                    @Override
                    public void onConnStateChange(KBeacon beacon, KBConnState state, int nReason) {
                        if (state == KBConnState.Connected) {
                            Log.i(TAG, "Connected successfully, sending stop ring command");
                            sendStopRingJson(beacon, mac);
                        } else if (state == KBConnState.Disconnected && nReason != 0) {
                            Log.e(TAG, "Stop ring connection failed, reason: " + nReason);
                        }
                    }
                });
            } else {
                // 이미 연결됨, 바로 중지 명령 전송
                Log.i(TAG, "Beacon already connected, sending stop ring command directly");
                sendStopRingJson(beacon, mac);
            }
            
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException in stop ring command for " + mac + ": " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error executing stop ring command for " + mac + ": " + e.getMessage());
        }
    }
    
    /**
     * Ring 중지 JSON 명령 전송
     */
    private void sendStopRingJson(KBeacon beacon, String mac) {
        try {
            org.json.JSONObject cmd = new org.json.JSONObject();
            cmd.put("msg", "ring");
            cmd.put("ringTime", 0); // 0ms = 즉시 중지
            cmd.put("ringType", 0x0); // 0x0=turn off (즉시 중지)
            
            Log.i(TAG, "Sending stop ring command: ringTime=0ms, ringType=0x0");
            
            beacon.sendCommand(cmd, new KBeacon.ActionCallback() {
                @Override
                public void onActionComplete(boolean bConfigSuccess, KBException error) {
                    if (bConfigSuccess) {
                        Log.i(TAG, "Stop ring command sent successfully for MAC: " + mac);
                    } else {
                        Log.e(TAG, "Stop ring command failed: " + (error != null ? error.errorCode : "unknown"));
                    }
                    
                    // 연결 해제 (명령 완료 후)
                    if (beacon.getState() == KBConnState.Connected) {
                        beacon.disconnect();
                        Log.d(TAG, "Disconnected after stop ring command");
                    }
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "sendStopRingJson error: " + e.getMessage());
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
                Log.d(TAG, String.format("Found existing beacon: %s, name=%s, state=%s", 
                    mac, beacon.getName(), beacon.getState()));
                return beacon;
            }
            
            // 매니저에서 찾을 수 없는 경우 - null 반환 (SDK 정책 준수)
            Log.w(TAG, "Beacon not found in KBeaconsMgr for MAC: " + mac);
            Log.w(TAG, "Cannot create KBeacon object directly - must be discovered through scanning");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in findBeaconByMac for " + mac + ": " + e.getMessage());
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
            
            // paired MAC만 배터리 업데이트
            if (pairedSet.contains(mac)) {
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
            // 레거시 MAC 게이트 레지스트리에서 paired로 마이그레이션
            Map<String, String> savedMacs = servicePrefs.getRegisteredMacs();
            for (String mac : savedMacs.keySet()) {
                if (!pairedSet.contains(mac)) {
                    pairedSet.add(mac);
                    DevicePrefs.addPaired(getApplicationContext(), mac);
                }
            }
            Log.i(TAG, String.format("Migrated %d MAC entries from legacy registry to paired", savedMacs.size()));
            
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
     * 하이브리드 스캔: MAC을 paired 목록에 등록 + DevicePrefs 저장 (레거시 호환)
     * @deprecated 대신 addPaired(String mac) 사용 (이미 저장 기능 포함)
     */
    @Deprecated
    public void registerMacForCollectionWithSave(String mac, String displayName) {
        if (mac != null) {
            addPaired(mac);
            Log.d(TAG, String.format("MAC registered for collection + saved (deprecated): %s -> %s", mac, displayName));
        }
    }
    
    /**
     * 하이브리드 스캔: MAC을 paired 목록에서 제거 + DevicePrefs 저장 (레거시 호환)
     * @deprecated 대신 removePaired(String mac) 사용 (이미 저장 기능 포함)
     */
    @Deprecated
    public void unregisterMacForCollectionWithSave(String mac) {
        if (mac != null) {
            removePaired(mac);
            Log.d(TAG, String.format("MAC unregistered from collection + saved (deprecated): %s", mac));
        }
    }
    
    // ========== 권한 체크 헬퍼 메서드들 (크래시 방지) ==========
    
    /**
     * BLUETOOTH_SCAN 권한 체크 (Android 버전별)
     * @return true if 권한 있음
     */
    private boolean hasBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else { // Android 10/11
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    /**
     * BLUETOOTH_CONNECT 권한 체크 (Android 버전별)
     * @return true if 권한 있음
     */
    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else { // Android 10/11 - 연결에도 ACCESS_FINE_LOCATION 필요
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    // ========== 크래시 스나이퍼 패치 메서드들 ==========
    
    /**
     * 크래시 스나이퍼 패치: 즉시 Foreground 시작 (5초 룰 회피)
     */
    private void startAsForegroundNow() {
        String channelId = "ble_scanner";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(channelId, "BLE Scanner", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        
        Notification notification = new NotificationCompat.Builder(this, channelId)
            .setContentTitle("Scanning beacons")
            .setContentText("Foreground scanning in progress")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build();
            
        startForeground(1001, notification);
        Log.d(TAG, "Foreground service started immediately (5-sec rule bypassed)");
    }
    
    /**
     * BLE 스캔/연결에 필요한 핵심 권한만 체크 (알림 권한 제외)
     */
    private boolean hasAllBlePerms() {
        if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else { // Android 10/11
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    /**
     * 크래시 스나이퍼 패치: Bluetooth ON 체크
     */
    private boolean isBtOn() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }
    
    /**
     * 크래시 스나이퍼 패치: 모든 BLE 작업의 공통 3중 게이트
     */
    public void ensureScanning() {
        Log.d(TAG, "ensureScanning called");
        
        // ③ 모든 BLE 작업의 공통 게이트
        if (!hasAllBlePerms() || !isBtOn()) {
            Log.w(TAG, String.format("BLE prerequisites not met: perms=%s, btOn=%s", 
                hasAllBlePerms(), isBtOn()));
            Intent intent = new Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            return;
        }
        
        if (isScanning) {
            Log.d(TAG, "Already scanning");
            return;
        }
        
        try {
            // 스캔 모드 설정 (이제 권한이 있으므로 안전)
            if (kBeaconsMgr != null) {
                kBeaconsMgr.setScanMode(KBeaconsMgr.SCAN_MODE_LOW_LATENCY);
            } else {
                kBeaconsMgr = KBeaconsMgr.sharedBeaconManager(getApplicationContext());
                if (kBeaconsMgr != null) {
                    kBeaconsMgr.delegate = this;
                    kBeaconsMgr.setScanMode(KBeaconsMgr.SCAN_MODE_LOW_LATENCY);
                }
            }
            
            // 실제 스캔 시작
            if (kBeaconsMgr != null) {
                int result = kBeaconsMgr.startScanning();
                if (result == 0) {
                    isScanning = true;
                    Log.i(TAG, "BLE scan started successfully via ensureScanning");
                    broadcastScanStateChanged(true);
                } else {
                    Log.e(TAG, "Failed to start BLE scan via ensureScanning, error: " + result);
                    // ★ 추가: 실패 시에도 폴백 브로드캐스트 발송
                    boolean locationEnabled = isLocationEnabled();
                    Intent i = new Intent(ACTION_SCAN_NO_RESULTS);
                    i.putExtra("location_enabled", locationEnabled);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(i);
                }
            } else {
                Log.e(TAG, "KBeaconsMgr is null, cannot start scanning");
            }
            
        } catch (SecurityException se) {
            Log.e(TAG, "ensureScanning SecurityException: " + se.getMessage());
            Intent intent = new Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        } catch (Throwable t) {
            Log.e(TAG, "ensureScanning fatal error", t);
        }
    }
    
    /**
     * 크래시 스나이퍼 패치: Bluetooth 상태 모니터링 시작
     */
    private void registerBtStateReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(btStateReceiver, filter);
        Log.d(TAG, "Bluetooth state receiver registered");
    }
    
    /**
     * Bluetooth 상태 변경 리시버 (업그레이드: 자동 복구 기능 포함)
     */
    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                Log.i(TAG, "Bluetooth state changed: " + getBluetoothStateName(state));
                
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        Log.w(TAG, "Bluetooth turned OFF - stopping scan and disconnecting all");
                        isScanning = false;
                        broadcastScanStateChanged(false);
                        safeDisconnectAll();
                        break;
                        
                    case BluetoothAdapter.STATE_ON:
                        Log.i(TAG, "Bluetooth turned ON - reinitializing and restarting scan");
                        reinitKBeaconMgr();
                        
                        // 짧은 지연 후 스캔 재시작 (어댑터 완전 초기화 대기)
                        mainHandler.postDelayed(() -> {
                            ensureScanning();
                            broadcastToast("Bluetooth 복구됨 - 스캔 재시작");
                        }, 1000);
                        break;
                        
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        Log.i(TAG, "Bluetooth turning OFF");
                        break;
                        
                    case BluetoothAdapter.STATE_TURNING_ON:
                        Log.i(TAG, "Bluetooth turning ON");
                        break;
                }
            }
        }
    };
    
    /**
     * Bluetooth 상태 이름 반환 (로깅용)
     */
    private String getBluetoothStateName(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF: return "OFF";
            case BluetoothAdapter.STATE_ON: return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF: return "TURNING_OFF";
            case BluetoothAdapter.STATE_TURNING_ON: return "TURNING_ON";
            default: return "UNKNOWN(" + state + ")";
        }
    }
    
    /**
     * 모든 연결 안전하게 해제
     */
    private void safeDisconnectAll() {
        for (BeaconState state : beaconStates.values()) {
            try {
                KBeacon beacon = findBeaconByMac(state.getMac());
                if (beacon != null && beacon.getState() == KBConnState.Connected) {
                    beacon.disconnect();
                    Log.d(TAG, "Disconnected beacon due to BT OFF: " + state.getMac());
                }
            } catch (Throwable ignored) {}
        }
    }
    
    /**
     * KBeacon 매니저 재초기화
     */
    private void reinitKBeaconMgr() {
        try {
            // KBeacon 매니저가 새로운 Bluetooth 상태를 인식하도록 재초기화
            if (kBeaconsMgr != null) {
                // 기존 리스너 정리
                kBeaconsMgr.delegate = null;
            }
            
            // 새로운 인스턴스로 재초기화
            kBeaconsMgr = KBeaconsMgr.sharedBeaconManager(this);
            if (kBeaconsMgr != null) {
                kBeaconsMgr.delegate = this;
                Log.i(TAG, "KBeacon manager reinitialized successfully");
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "Failed to reinitialize KBeacon manager", t);
            broadcastToast("Bluetooth 복구 실패 - 앱을 재시작해주세요");
        }
    };
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "BleService onDestroy");
        
        // Bluetooth 리시버 해제
        try {
            unregisterReceiver(btStateReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister Bluetooth receiver", e);
        }
        
        // 스캔 중지
        try { 
            stopScanning(); 
        } catch (Throwable ignored) {
            Log.w(TAG, "Exception during stop scanning", ignored);
        }
        
        // Ring 알람 중지
        try {
            stopAllRingAlarms();
        } catch (Throwable ignored) {
            Log.w(TAG, "Exception during stop ring alarms", ignored);
        }
        
        // 스케줄러 정리
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        
        // KBeaconsMgr 정리
        if (kBeaconsMgr != null) {
            kBeaconsMgr.delegate = null;
        }
        
        // 포그라운드 알림 제거
        stopForeground(true);
        
        super.onDestroy();
    }
    
    private boolean isLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } catch (Throwable t) {
            return false;
        }
    }
    
    // ==================== COMMAND GATE 패턴 구현 ====================
    
    /**
     * 커맨드 게이트: 모든 Ring 명령은 반드시 여기를 통해서만 실행
     * - 호출자/이유 추적 로깅
     * - 자동 OFF 정책 차단 (사용자만 OFF 가능)
     * - 오프라인 비콘 보호
     */
    private void issueRingCommand(String mac, boolean on, RingReason reason) {
        // 호출자/이유 로깅
        StackTraceElement caller = new Throwable().getStackTrace()[1];
        Log.i(TAG, String.format("[RING_CMD] mac=%s on=%s reason=%s at %s:%d",
                mac, on, reason, caller.getClassName(), caller.getLineNumber()));

        // 정책: 자동 OFF 금지 (사용자만 OFF 가능)
        if (!on && reason != RingReason.USER_TAP_OFF) {
            Log.w(TAG, "[RING_CMD] auto OFF blocked by policy, reason: " + reason);
            return;
        }

        // 오프라인 가드
        if (!isOnline(mac)) {
            setDesiredRingFlag(mac, on);
            String message = on ? "오프라인: 알람 시작 예약됨" : "오프라인: 알람 중지 예약됨";
            broadcastToast(message);
            Log.i(TAG, String.format("[RING_CMD] offline beacon %s, desired=%s", mac, on));
            return;
        }

        // JSON 명령 작성 (STOP=0x0 / START=0x1)
        org.json.JSONObject cmd = new org.json.JSONObject();
        try {
            cmd.put("msg", "ring");
            cmd.put("ringTime", on ? 3000 : 0);
            cmd.put("ringType", on ? 0x1 : 0x0);
        } catch (Exception e) {
            Log.e(TAG, "[RING_CMD] JSON creation error: " + e.getMessage());
            return;
        }

        // 기존 파이프라인 사용 (타임아웃/재시도)
        sendCommandWithConnect(
            mac, cmd, 3,
            on ? "알람 시작됨" : "알람 중지됨",
            on ? "알람 시작 실패" : "알람 중지 실패",
            beacon -> scheduleIdleDisconnect(beacon)
        );
    }
    
    /**
     * 비콘이 온라인 상태인지 확인
     */
    private boolean isOnline(String mac) {
        BeaconState state = beaconStates.get(mac);
        if (state == null) return false;
        
        long timeSinceLastSeen = System.currentTimeMillis() - state.getUpdatedAt();
        return timeSinceLastSeen <= BEACON_TTL_MS;
    }
    
    /**
     * 내부 플래그 설정 (상태 변경)
     */
    private void setDesiredRingFlag(String mac, boolean desired) {
        BeaconState state = beaconStates.get(mac);
        if (state != null) {
            state.desiredRing = desired;
            Log.d(TAG, String.format("setDesiredRingFlag: %s -> %s", mac, desired));
        }
    }
    
    /**
     * 공개 API: 플래그만 바꾸고, reconcile에서 커맨드 게이트 호출
     */
    public void setDesiredRingPublic(String mac, boolean on, RingReason reason) {
        BeaconState state = beaconStates.get(mac);
        if (state == null) {
            Log.w(TAG, "setDesiredRingPublic: beacon not found: " + mac);
            return;
        }
        
        if (state.desiredRing == on) {
            Log.v(TAG, String.format("setDesiredRingPublic: no change %s=%s", mac, on));
            return; // 변화 없으면 무시
        }
        
        state.desiredRing = on;
        Log.d(TAG, String.format("setDesiredRingPublic: %s -> %s (reason=%s)", mac, on, reason));
        
        reconcileDesiredState(mac, reason);
    }
    
    /**
     * 희망 상태와 실제 상태 동기화
     */
    private void reconcileDesiredState(String mac, RingReason reason) {
        BeaconState state = beaconStates.get(mac);
        if (state == null) return;

        // 자동 OFF는 금지(사용자 OFF만 허용)
        if (!state.desiredRing && reason != RingReason.USER_TAP_OFF) {
            Log.d(TAG, String.format("reconcileDesiredState: auto OFF blocked for %s, reason=%s", mac, reason));
            return;
        }

        issueRingCommand(mac, state.desiredRing, reason);
    }
    
    /**
     * 향상된 명령 전송 (onFinally 훅 지원)
     */
    private void sendCommandWithConnect(
        String mac, org.json.JSONObject cmd, int maxRetry,
        String successMsg, String failMsg,
        java.util.function.Consumer<KBeacon> onFinally
    ) {
        BeaconState state = beaconStates.get(mac);
        if (state == null) {
            Log.w(TAG, "sendCommandWithConnect: beacon not found: " + mac);
            return;
        }
        
        // 기존 연결 로직 재사용하되 onFinally 훅 추가
        performConnectAndCommand(mac, cmd, 0, maxRetry, successMsg, failMsg, onFinally);
    }
    
    /**
     * 실제 연결 및 명령 수행 (재귀 재시도 지원)
     */
    private void performConnectAndCommand(
        String mac, org.json.JSONObject cmd, int currentRetry, int maxRetry,
        String successMsg, String failMsg,
        java.util.function.Consumer<KBeacon> onFinally
    ) {
        if (currentRetry >= maxRetry) {
            Log.e(TAG, String.format("performConnectAndCommand: max retry exceeded for %s", mac));
            return;
        }
        
        // 연결 시도
        BeaconState state = beaconStates.get(mac);
        if (state == null) return;
        
        // 실제 beacon 객체 찾기 (findBeaconByMac 사용 - 없으면 생성)
        KBeacon beacon = findBeaconByMac(mac);
        
        if (beacon == null) {
            Log.e(TAG, "performConnectAndCommand: KBeacon object not found for " + mac);
            broadcastToast("비콘을 찾을 수 없습니다. 조금만 가까이 접근한 뒤 다시 시도하세요.");
            broadcastRingStateChanged(mac, "알람");
            return;
        }
        
        final KBeacon finalBeacon = beacon;
        
        // 연결 상태 확인
        if (beacon.getState() == KBConnState.Connected) {
            // 이미 연결됨 - 바로 명령 전송
            sendCommandToConnectedBeacon(finalBeacon, cmd, successMsg, failMsg, onFinally);
        } else {
            // 연결 시도 - 권한/예외 가드 적용
            Log.d(TAG, "performConnectAndCommand: connecting to " + mac);
            
            try {
                // Android 12+ BLUETOOTH_CONNECT 권한 체크
                if (android.os.Build.VERSION.SDK_INT >= 31 &&
                    androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for " + mac);
                    android.content.Intent intent = new android.content.Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                    return;
                }
                
                // 기본 패스워드로 연결 시도
                final String defaultPassword = "0000000000000000";
                beacon.connect(defaultPassword, 20000, new KBeacon.ConnStateDelegate() {
                    @Override
                    public void onConnStateChange(KBeacon beacon, KBConnState state, int nReason) {
                        if (state == KBConnState.Connected) {
                            Log.d(TAG, "performConnectAndCommand: connected to " + mac);
                            sendCommandToConnectedBeacon(finalBeacon, cmd, successMsg, failMsg, onFinally);
                        } else if (state == KBConnState.Disconnected) {
                            Log.w(TAG, "performConnectAndCommand: connection failed for " + mac + ", retry " + (currentRetry + 1));
                            // 재시도
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> 
                                performConnectAndCommand(mac, cmd, currentRetry + 1, maxRetry, successMsg, failMsg, onFinally), 
                                1000);
                        }
                    }
                });
                
            } catch (SecurityException se) {
                Log.e(TAG, "SecurityException during connect for " + mac + ": " + se.getMessage());
                android.content.Intent intent = new android.content.Intent("com.kkmcn.sensordemo.NEED_PERMISSIONS");
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            } catch (Throwable t) {
                Log.e(TAG, "connect() threw exception for " + mac + ": " + t.getMessage(), t);
                // 재시도 또는 실패 처리
                if (currentRetry + 1 < maxRetry) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> 
                        performConnectAndCommand(mac, cmd, currentRetry + 1, maxRetry, successMsg, failMsg, onFinally), 
                        2000); // 예외 시 더 긴 대기시간
                } else {
                    Log.e(TAG, "All connection attempts failed for " + mac);
                }
            }
        }
    }
    
    /**
     * 연결된 비콘에 명령 전송
     */
    private void sendCommandToConnectedBeacon(
        KBeacon beacon, org.json.JSONObject cmd,
        String successMsg, String failMsg,
        java.util.function.Consumer<KBeacon> onFinally
    ) {
        beacon.sendCommand(cmd, new KBeacon.ActionCallback() {
            @Override
            public void onActionComplete(boolean success, KBException error) {
                if (success) {
                    Log.i(TAG, successMsg + " for MAC: " + beacon.getMac());
                } else {
                    Log.e(TAG, failMsg + " for MAC: " + beacon.getMac() + 
                        ", error: " + (error != null ? error.errorCode : "unknown"));
                }
                
                // onFinally 훅 실행 (null 가드)
                if (onFinally != null && beacon != null) {
                    onFinally.accept(beacon);
                }
            }
        });
    }
    
    /**
     * 유휴 연결 해제 스케줄링
     */
    private static final long IDLE_DISCONNECT_MS = 1500;
    private final java.util.concurrent.ConcurrentHashMap<String, Runnable> pendingDisconnects = new java.util.concurrent.ConcurrentHashMap<>();
    
    private void scheduleIdleDisconnect(KBeacon beacon) {
        if (beacon == null) {
            Log.w(TAG, "scheduleIdleDisconnect: beacon is null, skipping");
            return;
        }
        
        String mac = beacon.getMac();
        
        // 기존 예약 취소
        Runnable existing = pendingDisconnects.remove(mac);
        if (existing != null) {
            mainHandler.removeCallbacks(existing);
        }
        
        // 새로운 연결 해제 예약
        Runnable disconnectTask = () -> {
            if (beacon.getState() == KBConnState.Connected) {
                beacon.disconnect();
                Log.d(TAG, "scheduleIdleDisconnect: disconnected " + mac);
            }
            pendingDisconnects.remove(mac);
        };
        
        pendingDisconnects.put(mac, disconnectTask);
        mainHandler.postDelayed(disconnectTask, IDLE_DISCONNECT_MS);
        
        Log.d(TAG, "scheduleIdleDisconnect: scheduled for " + mac + " in " + IDLE_DISCONNECT_MS + "ms");
    }
    
    /**
     * 토스트 브로드캐스트
     */
    private void broadcastToast(String message) {
        Intent i = new Intent(ACTION_TOAST);
        i.putExtra("message", message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        Log.i(TAG, "[TOAST->UI] " + message);
    }
    
    // ==================== Watchdog 시스템 ====================
    
    /**
     * Watchdog 시작 - 스캔 상태 감시 및 자동 복구
     */
    private void startScanWatchdog() {
        wdHandler.removeCallbacks(wdTask);
        wdHandler.postDelayed(wdTask, WATCHDOG_PERIOD_MS);
        Log.i(TAG, "Watchdog started");
    }
    
    private final Runnable wdTask = new Runnable() {
        @Override 
        public void run() {
            try {
                long now = System.currentTimeMillis();
                
                // 광고 수신이 오랫동안 없으면 스캔 재시작
                if (now - lastAdvAt > NO_ADV_RESTART_MS) {
                    Log.w(TAG, "Watchdog: no advertisement for " + (now - lastAdvAt) + "ms, restarting scan");
                    restartBleScan();
                    cleanupStuckConnections();
                }
                
                // 주기적 예방 재시작 (30분마다)
                if (now - lastScanRestartAt > PERIODIC_SCAN_RESTART_MS) {
                    Log.i(TAG, "Watchdog: periodic scan restart");
                    restartBleScan();
                }
                
            } catch (Throwable t) { 
                Log.e(TAG, "Watchdog error", t); 
            }
            
            // 다음 체크 예약
            wdHandler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };
    
    /**
     * 광고 수신 시 호출 - Watchdog용 타임스탬프 갱신
     */
    private void onAnyAdvertisementObserved() {
        lastAdvAt = System.currentTimeMillis();
    }
    
    /**
     * 스캔 재시작 (드라이버 상태 리셋)
     */
    private void restartBleScan() {
        try { 
            stopScanning(); 
        } catch (Throwable ignored) {}
        
        try { 
            startScanning(); 
        } catch (Throwable t) {
            Log.e(TAG, "restartBleScan failed", t);
        }
        
        lastScanRestartAt = System.currentTimeMillis();
        Log.i(TAG, "BLE scan restarted");
    }
    
    /**
     * 연결이 오래 지속되는 장치 정리
     */
    private void cleanupStuckConnections() {
        for (BeaconState state : beaconStates.values()) {
            String mac = state.getMac();
            // 연결 시도가 30초 이상 지속되면 강제 정리
            if (isConnectingTooLong(state)) {
                forceCloseGatt(mac);
            }
        }
    }
    
    /**
     * 연결이 너무 오래 지속되는지 확인
     */
    private boolean isConnectingTooLong(BeaconState state) {
        // 연결 상태 확인 로직 (간단히 30초 기준)
        long now = System.currentTimeMillis();
        return (now - state.getUpdatedAt()) > 30000;
    }
    
    /**
     * GATT 연결 강제 정리
     */
    private void forceCloseGatt(String mac) {
        KBeacon beacon = findBeaconByMac(mac);
        if (beacon != null) {
            try { 
                beacon.disconnect(); 
                Log.i(TAG, "Force disconnected stuck beacon: " + mac);
            } catch (Throwable ignored) {}
        }
    }
    
    // ==================== STOP 최우선 처리 시스템 ====================
    
    /**
     * 즉시 STOP 명령 처리 - 모든 대기 중인 명령보다 우선
     */
    public void requestRingStopImmediate(String mac) {
        Log.i(TAG, "Immediate STOP requested for: " + mac);
        
        // 1) 해당 MAC의 모든 pending ring 작업 취소
        cancelAllRingSchedules(mac);
        
        // 2) STOP 명령 생성
        org.json.JSONObject stopCmd = new org.json.JSONObject();
        try {
            stopCmd.put("msg", "ring");
            stopCmd.put("ringTime", 0);
            stopCmd.put("ringType", 0x0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create STOP command", e);
            return;
        }
        
        // 3) 최우선 전송
        sendStopWithPriority(mac, stopCmd);
    }
    
    /**
     * 해당 MAC의 모든 링 스케줄 취소
     */
    private void cancelAllRingSchedules(String mac) {
        // 기존 스케줄된 링 작업 취소
        ScheduledFuture<?> existingTask = activeRingTasks.get(mac);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(true);
            activeRingTasks.remove(mac);
            Log.d(TAG, "Cancelled existing ring task for: " + mac);
        }
        
        // 진행 중인 링 상태 정리
        ringInProgress.put(mac, false);
        
        // 내부 desired 플래그도 false로
        setDesiredRingFlag(mac, false);
    }
    
    /**
     * 최우선으로 STOP 명령 전송
     */
    private void sendStopWithPriority(String mac, org.json.JSONObject cmd) {
        KBeacon beacon = findBeaconByMac(mac);
        
        // 이미 연결되어 있으면 즉시 전송
        if (beacon != null && beacon.getState() == KBConnState.Connected) {
            Log.d(TAG, "Beacon already connected, sending STOP immediately: " + mac);
            writeRingCommandImmediate(beacon, cmd);
            return;
        }
        
        // 연결이 필요하면 짧은 타임아웃으로 빠른 연결 시도
        if (beacon != null) {
            Log.d(TAG, "Connecting for immediate STOP: " + mac);
            connectThenStop(mac, cmd);
        } else {
            Log.w(TAG, "Beacon not found for STOP: " + mac);
            broadcastToast("비콘을 찾을 수 없습니다: " + mac);
            broadcastRingStateChanged(mac, "알람");
        }
    }
    
    /**
     * 빠른 연결 후 STOP 전송
     */
    private void connectThenStop(String mac, org.json.JSONObject cmd) {
        final int STOP_CONNECT_TIMEOUT = 5000; // 5초 타임아웃
        
        KBeacon beacon = findBeaconByMac(mac);
        if (beacon == null) return;
        
        String password = "0000000000000000"; // 기본 패스워드
        
        beacon.connect(password, STOP_CONNECT_TIMEOUT, new KBeacon.ConnStateDelegate() {
            @Override
            public void onConnStateChange(KBeacon beacon, KBConnState state, int nReason) {
                if (state == KBConnState.Connected) {
                    Log.d(TAG, "Connected for STOP, sending command: " + mac);
                    writeRingCommandImmediate(beacon, cmd);
                } else if (state == KBConnState.Disconnected && nReason != 0) {
                    Log.w(TAG, "Failed to connect for STOP: " + mac + ", reason: " + nReason);
                    broadcastToast("연결 실패: STOP 명령 전달 불가");
                    broadcastRingStateChanged(mac, "알람");
                }
            }
        });
    }
    
    /**
     * 즉시 링 명령 전송 (재시도 포함)
     */
    private void writeRingCommandImmediate(KBeacon beacon, org.json.JSONObject cmd) {
        String mac = beacon.getMac();
        
        beacon.sendCommand(cmd, new KBeacon.ActionCallback() {
            @Override
            public void onActionComplete(boolean bConfigSuccess, KBException error) {
                if (bConfigSuccess) {
                    Log.d(TAG, "STOP command sent successfully: " + mac);
                    broadcastRingStateChanged(mac, "알람");
                    
                    // 짧은 지연 후 연결 해제
                    mainHandler.postDelayed(() -> {
                        try {
                            beacon.disconnect();
                        } catch (Exception ignored) {}
                    }, 500);
                    
                } else {
                    Log.w(TAG, "STOP command failed, retrying: " + mac + ", error: " + 
                        (error != null ? error.errorCode : "unknown"));
                    
                    // 1회 재시도
                    mainHandler.postDelayed(() -> {
                        beacon.sendCommand(cmd, new KBeacon.ActionCallback() {
                            @Override
                            public void onActionComplete(boolean retryResult, KBException retryError) {
                                broadcastRingStateChanged(mac, "알람");
                                
                                if (retryResult) {
                                    Log.d(TAG, "STOP command retry successful: " + mac);
                                } else {
                                    Log.e(TAG, "STOP command retry failed: " + mac + ", error: " + 
                                        (retryError != null ? retryError.errorCode : "unknown"));
                                    broadcastToast("부저 알람 중지 실패: " + mac);
                                }
                                
                                // 연결 해제
                                try { beacon.disconnect(); } catch (Exception ignored) {}
                            }
                        });
                    }, 1000);
                }
            }
        });
    }
    
    
    // ==================== 기존 메서드 래핑 (하위 호환성) ====================
}