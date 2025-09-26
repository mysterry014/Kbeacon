/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kkmcn.sensordemo;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.location.LocationManager;
import android.provider.Settings;
import android.net.Uri;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import android.view.MotionEvent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAccSensorValue;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketBase;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEBeacon;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyTLM;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyUID;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyURL;
// iBeacon import removed - using KBeacon protocol only
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor;
import com.kkmcn.kbeaconlib2.KBConnState;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSystem;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvType;
import com.kkmcn.kbeaconlib2.KBeacon;
import com.kkmcn.kbeaconlib2.KBeaconsMgr;
import com.kkmcn.sensordemo.data.BeaconDataStore;
import com.kkmcn.sensordemo.data.Prefs;
import com.kkmcn.sensordemo.model.BeaconState;
import com.kkmcn.sensordemo.utils.RssiFilter;
import com.kkmcn.sensordemo.utils.DistanceEstimator;
// RingManager 제거: BleService로 단일화
import com.kkmcn.sensordemo.battery.BatteryScheduler;
import com.kkmcn.sensordemo.cal.CalibrationSession;
import com.kkmcn.sensordemo.cal.CalibrationDialog;
import com.kkmcn.sensordemo.service.BleService;

import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.appcompat.app.ActionBar;
import androidx.core.app.ActivityCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;


/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
public class DeviceScanActivity extends AppBaseActivity implements View.OnClickListener, AdapterView.OnItemClickListener,
        LeDeviceListAdapter.ListDataSource, LeDeviceListAdapter.OnRowActionListener {
	private final static String TAG = "Beacon.ScanAct";//DeviceScanActivity.class.getSimpleName();

    private static final String LOG_TAG = "ScanExample";

    private static final int PERMISSION_COARSE_LOCATION = 22;
    private static final int PERMISSION_FINE_LOCATION = 23;
    private static final int PERMISSION_SCAN = 24;
    private static final int PERMISSION_CONNECT = 25;
    private static final int REQ_PERMS = 1001; // 통합 권한 요청
    private static final int REQ_BT_ON = 1002; // 블루투스 활성화 요청
    
    // 권한 요청 이력 관리용 SharedPreferences 키
    private static final String PREF_PERM = "perm_prefs";
    private static final String KEY_ASKED_FINE = "asked_fine_once";
    
    // 권한 요청 디바운스 플래그
    private boolean askingRuntimePerms = false;


    private ListView mListView;
    private LeDeviceListAdapter mDevListAdapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private int mScanFailedContinueNum = 0;

    private final static int  MAX_ERROR_SCAN_NUMBER = 2;
    private HashMap<String, KBeacon> mBeaconsDictory;
    private KBeacon[] mBeaconsArray;
    private KBeaconsMgr mBeaconsMgr; // TODO: BleService로 대체 예정
    
    // BleService 바인딩
    private BleService mBleService;
    private boolean mServiceBound = false;
    
    // 캘리브레이션 저장 보류 큐 (서비스 연결 전 요청 처리)
    private java.util.Queue<PendingCalibrationSave> pendingCalibrationSaves = new java.util.LinkedList<>();
    
    private static class PendingCalibrationSave {
        String mac;
        String displayName;
        CalibrationSession.CalibrationResult result;
        
        PendingCalibrationSave(String mac, String displayName, CalibrationSession.CalibrationResult result) {
            this.mac = mac;
            this.displayName = displayName;
            this.result = result;
        }
    }


    private Button mBtnFilterTotal, mBtnRmvAllFilter, mBtnFilterArrow, mBtnRmvNameFilter;
    private TextView mTxtViewRssi;
    private SeekBar mSeekBarRssi;
    private int mRssiFilterValue;
    private EditText mEditFltDevName;
    private String mFilterName = "";
    private LinearLayout mLayoutFilterName, mLayoutFilterRssi;
    
    // 하단 폰 알람 버튼들만 유지
    private Button mBtnPhoneAlarm, mBtnPhoneAlarmStop;
    
    // Phase 2 데이터 처리 컴포넌트들
    private BeaconDataStore mBeaconDataStore;
    private ConcurrentHashMap<String, RssiFilter> mRssiFilters;
    private ConcurrentHashMap<String, DistanceEstimator> mDistanceEstimators;
    
    // Phase 3 기능 컴포넌트들
    // RingManager 제거: BleService로 단일화
    private BatteryScheduler mBatteryScheduler;
    private MediaPlayer mPhoneAlarmPlayer;
    
    // Phase 2 Part 3: 영속화 컴포넌트
    private Prefs mPrefs;
    
    // 500ms UI 갱신용
    private Handler mUiUpdateHandler;
    private Runnable mUiUpdateRunnable;
    private static final int UI_UPDATE_INTERVAL_MS = 500;
    
    // [패치 D] UI 갱신 스로틀링 - 연타 방지용
    private volatile long lastUiUpdateTime = 0;
    private static final long UI_UPDATE_THROTTLE_MS = 500;
    
    // [터치디바운스] UI 갱신 제어 플래그
    private volatile boolean userTouchingList = false;
    private volatile long uiFreezeUntilMs = 0L;
    
    // 브로드캐스트 리시버 중복 등록 방지
    private boolean receiverRegistered = false;

    // 캘리브레이션 실시간 샘플 간단 로거(필요 시 UI 갱신에 활용)
    private final java.util.concurrent.ConcurrentHashMap<String, String> calibStageRssi = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 토스트 디바운스 (중복 방지)
    private String lastToastText = "";
    private long lastToastTime = 0;
    private static final long TOAST_DEBOUNCE_MS = 1000; // 1초 내 동일 문구 무시
    
    // 버튼 상태 추적 (CLAUDE.md 스타일 상태 표시)
    private final java.util.concurrent.ConcurrentHashMap<String, String> buttonStates = new java.util.concurrent.ConcurrentHashMap<>();
    
    // [캘리브레이션] 가드 플래그 및 세션 관리
    private volatile boolean isCalibrating = false;
    private CalibrationSession activeCalibrationSession = null;
    
    // 디스플레이용 이름(별칭→광고이름→MAC 순) 해석
    private String resolveDisplayName(String mac) {
        try {
            if (mBeaconDataStore != null) {
                BeaconState s = mBeaconDataStore.get(mac);
                if (s != null) {
                    if (s.getAlias() != null && !s.getAlias().isEmpty()) return s.getAlias();
                    if (s.getName() != null && !s.getName().isEmpty())   return s.getName();
                }
            }
            // BleService에서 beacon 상태는 별도 방법으로 조회 필요
            // 현재는 mBeaconDataStore만 사용
        } catch (Throwable ignored) {}
        return mac != null ? mac : "Unknown";
    }
    
    // BleService 연결 관리
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.d(TAG, "BleService connected");
            BleService.BleServiceBinder binder = (BleService.BleServiceBinder) iBinder;
            mBleService = binder.getService();
            mServiceBound = true;
            
            // Service 연결 후 초기 상태 동기화
            if (mBleService != null) {
                // Activity의 UI를 Service 상태와 동기화
                invalidateOptionsMenu();
                
                // 보류된 캘리브레이션 저장 요청들 처리
                processPendingCalibrationSaves();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG, "BleService disconnected");
            mBleService = null;
            mServiceBound = false;
        }
    };
    
    // BleService 브로드캐스트 리시버
    private final BroadcastReceiver mServiceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            
            switch (action) {
                case BleService.ACTION_BEACON_UPDATE:
                    // 비콘 목록 업데이트 (500ms 주기)
                    if (mServiceBound && mBleService != null) {
                        runOnUiThread(() -> updateUiFromService());
                    }
                    break;
                    
                case BleService.ACTION_SCAN_STATE_CHANGED:
                    boolean scanning = intent.getBooleanExtra("scanning", false);
                    Log.d(TAG, "Scan state changed: " + scanning);
                    runOnUiThread(() -> {
                        invalidateOptionsMenu();
                        if (swipeRefreshLayout != null) {
                            swipeRefreshLayout.setRefreshing(scanning);
                        }
                    });
                    break;
                    
                case BleService.ACTION_RING_STATE_CHANGED:
                    String ringMac = intent.getStringExtra("mac");
                    String ringState = intent.getStringExtra("state");
                    Log.d(TAG, String.format("Ring state changed: MAC=%s, state=%s", ringMac, ringState));
                    
                    // 버튼 상태 업데이트 (CLAUDE.md 스타일)
                    updateButtonState(ringMac, ringState);
                    
                    // origin 정보 기반 토스트 정책 (수동 시작/중지만 토스트 표시)
                    String origin = intent.getStringExtra("origin");
                    
                    if ("알람중".equals(ringState) && "MANUAL_START".equals(origin)) {
                        toastShow(resolveDisplayName(ringMac) + " 부저 알람 시작");
                        Log.i(TAG, String.format("[TOAST-POLICY] Manual start toast: mac=%s, origin=%s", ringMac, origin));
                    } else if ("알람".equals(ringState) && "MANUAL_STOP".equals(origin)) {
                        toastShow(resolveDisplayName(ringMac) + " 부저 알람 중지");
                        Log.i(TAG, String.format("[TOAST-POLICY] Manual stop toast: mac=%s, origin=%s", ringMac, origin));
                    } else {
                        Log.v(TAG, String.format("[TOAST-POLICY] Skipped toast: mac=%s, state=%s, origin=%s", ringMac, ringState, origin));
                    }
                    // AUTO_START, SCHED_RETRIGGER는 토스트 표시 금지 (버튼 상태만 갱신)
                    break;
                    
                case BleService.ACTION_AUTO_ALARM_TRIGGERED: {
                    String triggerMac = intent.getStringExtra("mac");
                    toastShow("자동 알람 시작: " + resolveDisplayName(triggerMac));
                    break;
                }


                case BleService.ACTION_CALIBRATION_SAMPLE: {
                    // extras: mac, stage, rssi(int), done(boolean)
                    String calibMac = intent.getStringExtra("mac");
                    int calibRssi = intent.getIntExtra("rssi", Integer.MIN_VALUE);
                    String calibStage = intent.getStringExtra("stage");
                    boolean calibDone = intent.getBooleanExtra("done", false);
                    
                    // 캘리브레이션 진행 중이고 세션이 활성화되어 있을 때만 처리
                    if (isCalibrating && activeCalibrationSession != null && calibMac != null) {
                        String sessionMac = activeCalibrationSession.getMac();
                        // 타겟 MAC이 일치하고 RSSI가 유효할 때 세션에 전달 (무효 샘플은 Service에서 이미 필터링됨)
                        if (calibMac.equalsIgnoreCase(sessionMac) && 
                            calibRssi != Integer.MIN_VALUE) {
                            
                            activeCalibrationSession.onRssiSample(calibRssi);
                            Log.v(TAG, String.format("[CALIB-FEED] %s: %d dBm → session", 
                                resolveDisplayName(calibMac), calibRssi));
                        }
                    }
                    
                    // 기존 로깅 유지
                    String calibKey = calibMac + "/" + (calibStage != null ? calibStage : "sampling");
                    calibStageRssi.put(calibKey, (calibRssi == Integer.MIN_VALUE ? "N/A" : (calibRssi + " dBm")));
                    Log.d(TAG, String.format("CALIB SAMPLE %s [%s] = %s, active=%s", 
                        resolveDisplayName(calibMac), calibStage, calibStageRssi.get(calibKey), 
                        (activeCalibrationSession != null)));
                    break;
                }
                
                case BleService.ACTION_CALIB_STAGE_COMPLETE: {
                    // extras: mac, stageIndex, medianRssi, keptSamples
                    String calibMac = intent.getStringExtra("mac");
                    int stageIndex = intent.getIntExtra("stageIndex", -1);
                    double medianRssi = intent.getDoubleExtra("medianRssi", 0.0);
                    int keptSamples = intent.getIntExtra("keptSamples", 0);
                    
                    Log.d(TAG, String.format("[CALIB-STAGE-COMPLETE] Stage %d for %s: median=%.1fdBm, samples=%d", 
                          stageIndex + 1, resolveDisplayName(calibMac), medianRssi, keptSamples));
                    
                    // 캘리브레이션 다이얼로그나 세션이 이 신호를 처리하여 자동으로 다음 단계로 진행
                    // (실제 단계 전환은 CalibrationSession 내부에서 이미 처리됨)
                    break;
                }
                
                case BleService.ACTION_CALIB_STAGE_STARTED: {
                    // extras: mac, stageIndex, distanceMeters
                    String calibMac = intent.getStringExtra("mac");
                    int stageIndex = intent.getIntExtra("stageIndex", -1);
                    double distanceMeters = intent.getDoubleExtra("distanceMeters", 0.0);
                    
                    Log.d(TAG, String.format("[CALIB-STAGE-STARTED] Stage %d for %s: distance=%.1fm", 
                          stageIndex + 1, resolveDisplayName(calibMac), distanceMeters));
                    
                    // 단계 시작 브로드캐스트 (CalibrationDialog가 카운트다운을 시작함)
                    break;
                }
                
                case BleService.ACTION_CALIBRATION_ERROR: {
                    // extras: mac, message
                    String errorMac = intent.getStringExtra("mac");
                    String errorMessage = intent.getStringExtra("message");
                    
                    Log.e(TAG, String.format("[CALIB-ERROR] MAC: %s, Error: %s", errorMac, errorMessage));
                    
                    runOnUiThread(() -> {
                        toastShow("캘리브레이션 오류: " + errorMessage);
                        isCalibrating = false;
                        activeCalibrationSession = null;
                        
                        // 캘리브레이션 모드 해제
                        if (mServiceBound && mBleService != null) {
                            mBleService.setCalibrationMode(false, null);
                            mBleService.clearCalibrationTarget();
                        }
                        
                        // UI 업데이트
                        BeaconState beaconState = mBeaconDataStore.get(errorMac);
                        if (beaconState != null) {
                            beaconState.setCalibrationInProgress(false);
                            if (mDevListAdapter != null) {
                                mDevListAdapter.notifyDataSetChanged();
                            }
                        }
                    });
                    break;
                }
                    
                case BleService.ACTION_SCAN_NO_RESULTS:
                    boolean locEnabled = intent.getBooleanExtra("location_enabled", false);
                    Log.w(TAG, "SCAN_NO_RESULTS received. location_enabled=" + locEnabled);

                    // 1) 위치 설정 OFF면 설정 화면 유도
                    if (!isLocationEnabledSafe()) {
                        try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception ignored) {}
                    }

                    // 2) 12+ 폴백: FINE_LOCATION 최초 요청 보장 + '다시 묻지 않음' 구분
                    if (Build.VERSION.SDK_INT >= 31) {
                        if (ActivityCompat.checkSelfPermission(DeviceScanActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                                != PackageManager.PERMISSION_GRANTED) {

                            android.content.SharedPreferences sp = DeviceScanActivity.this.getSharedPreferences(PREF_PERM, MODE_PRIVATE);
                            boolean askedBefore = sp.getBoolean(KEY_ASKED_FINE, false);

                            if (!askedBefore) {
                                // 최초 요청: 무조건 다이얼로그
                                requestPermsOnce(
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    PERMISSION_FINE_LOCATION
                                );
                                sp.edit().putBoolean(KEY_ASKED_FINE, true).apply();
                            } else {
                                // 이전에 거절한 적 있음 → '다시 묻지 않음' 여부 판단
                                if (ActivityCompat.shouldShowRequestPermissionRationale(DeviceScanActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                                    requestPermsOnce(
                                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                        PERMISSION_FINE_LOCATION
                                    );
                                } else {
                                    // '다시 묻지 않음'
                                    Toast.makeText(DeviceScanActivity.this, "위치 권한을 켜야 근처 기기를 스캔할 수 있습니다. 앱 설정으로 이동합니다.", Toast.LENGTH_SHORT).show();
                                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", DeviceScanActivity.this.getPackageName(), null));
                                    DeviceScanActivity.this.startActivity(i);
                                }
                            }
                        }
                    }

                    // 3) 권한/설정 조치 후 재시작 (2s 지연)
                    mListView.postDelayed(() -> {
                        if (mServiceBound && mBleService != null) {
                            mBleService.stopScanning();
                            mBleService.startScanning();
                        } else {
                            startBleServiceSafely();
                        }
                    }, 2000);

                    break;
                    
                case BleService.ACTION_TOAST: {
                    String msg = intent.getStringExtra("message");
                    if (msg != null && !msg.isEmpty()) runOnUiThread(() -> toastShow(msg));
                    break;
                }
                
                case "com.kkmcn.sensordemo.NEED_PERMISSIONS": {
                    runOnUiThread(() -> {
                        try { 
                            checkBluetoothPermitAllowed(); 
                        } catch (Exception e) { 
                            toastShow("권한 요청 실패: " + e.getMessage()); 
                        }
                    });
                    break;
                }
            }
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        
        // Service 기반으로만 스캔 상태 확인 (KBeaconsMgr 직접 호출 제거)
        boolean isScanning = false;
        if (mServiceBound && mBleService != null) {
            isScanning = mBleService.isScanningActive();
        }
        
        Log.d(TAG, "onCreateOptionsMenu - Service bound: " + mServiceBound + 
                  ", Scanning: " + isScanning);
        
        if (isScanning) {
            menu.findItem(R.id.menu_stop).setVisible(true);
            menu.findItem(R.id.menu_scan).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(
                    R.layout.actionbar_indeterminate_progress);
        } else {
            menu.findItem(R.id.menu_stop).setVisible(false);
            menu.findItem(R.id.menu_scan).setVisible(true);
            menu.findItem(R.id.menu_refresh).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(null);
        }
        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main_activity);
        ActionBar actionBar = getSupportActionBar();
        if(actionBar!=null){
            actionBar.setDisplayHomeAsUpEnabled(false);
        }
        setTitle(R.string.device_list);

        mBeaconsDictory = new HashMap<>(50);

        // Phase 2 데이터 처리 컴포넌트 초기화
        mBeaconDataStore = BeaconDataStore.getInstance();
        mRssiFilters = new ConcurrentHashMap<>();
        mDistanceEstimators = new ConcurrentHashMap<>();
        
        // Phase 2 Part 3: 영속화 컴포넌트 초기화
        mPrefs = new Prefs(getApplicationContext());
        
        // 500ms UI 갱신 시스템 초기화 (Service 기반으로 변경)
        mUiUpdateHandler = new Handler();
        mUiUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                // [터치디바운스] 사용자 터치 중이거나 프리즈 기간에는 UI 갱신 스킵
                final long now = SystemClock.uptimeMillis();
                if (!userTouchingList && now >= uiFreezeUntilMs) {
                    // [패치 B] 서비스 바운드되어 있으면 타이머 갱신은 스킵 → 중복 notifyDataSetChanged() 제거
                    // 서비스 미바운드/중단일 때만 폴백 갱신 수행
                    if (!(mServiceBound && mBleService != null)) {
                        updateUiFromDataStore(); // 폴백만
                        Log.v(TAG, "UI update from DataStore (fallback mode)");
                    } else {
                        // 서비스 바운드 상태에서는 브로드캐스트만 신뢰
                        Log.v(TAG, "UI update skipped - using broadcast from service");
                    }
                } else {
                    Log.v("UI_UPDATE", String.format("Skipping UI update - touching=%s, frozen=%s", 
                        userTouchingList, (now < uiFreezeUntilMs)));
                }
                mUiUpdateHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
            }
        };

        // KBeaconsMgr는 BleService에서만 사용 - Activity에서는 접근하지 않음
        // mBeaconsMgr = KBeaconsMgr.sharedBeaconManager(this);
        // if (mBeaconsMgr == null)
        // {
        //     toastShow("make sure the phone has support ble funtion");
        //     finish();
        //     return;
        // }
        // delegate는 BleService가 독점 - Activity에서 설정하지 않음
        // mBeaconsMgr.delegate = this;
        // mBeaconsMgr.setScanMode(KBeaconsMgr.SCAN_MODE_LOW_LATENCY);
        mListView = (ListView) findViewById(R.id.listview);
        mDevListAdapter = new LeDeviceListAdapter(this, getApplicationContext());
        mDevListAdapter.setOnRowActionListener(this); // Phase 3: 콜백 리스너 연결
        mListView.setAdapter(mDevListAdapter);
        // [수정2] 행 전체 클릭 비활성화 - 오직 이름 TextView 클릭만 허용
        mListView.setOnItemClickListener(null);
        
        // [터치개선] ListView 하이라이트 제거 및 자식 우선 포커스
        mListView.setSelector(android.R.color.transparent);
        mListView.setCacheColorHint(android.R.color.transparent);
        mListView.setItemsCanFocus(true);   // 자식 뷰(버튼/텍스트)가 먼저 터치 받도록
        
        // [터치디바운스] 터치 중에는 UI 갱신 중단
        mListView.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    userTouchingList = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 손 뗀 직후 200ms 동안도 프리즈 유지 (다운/업 사이 리바인딩 충돌 방지)
                    uiFreezeUntilMs = SystemClock.uptimeMillis() + 200;
                    userTouchingList = false;
                    break;
            }
            return false; // 터치 이벤트를 자식으로 계속 전파
        });


        //total filter information
        mBtnFilterTotal = (Button) findViewById(R.id.btnFilterInfo);
        mBtnFilterTotal.setOnClickListener(this);
        mBtnRmvAllFilter = (Button) findViewById(R.id.btnRemoveAllFilter);
        mBtnRmvAllFilter.setOnClickListener(this);
        mBtnRmvAllFilter.setVisibility(View.GONE);
        mBtnFilterArrow = (Button) findViewById(R.id.imageButtonArrow);
        mBtnFilterArrow.setOnClickListener(this);
        mBtnFilterArrow.setTag(0);

        //filter layout
        mLayoutFilterName = (LinearLayout) findViewById(R.id.layFilterName);
        mLayoutFilterName.setVisibility(View.GONE);


        mLayoutFilterRssi = (LinearLayout) findViewById(R.id.layRssiFilter);
        mLayoutFilterRssi.setVisibility(View.GONE);

        //remove action
        findViewById(R.id.btmRemoveFilterName).setOnClickListener(this);

        mRssiFilterValue = -100;
        mSeekBarRssi = (SeekBar) findViewById(R.id.seekBarRssiFilter);
        mSeekBarRssi.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mRssiFilterValue = progress - 100;
                String strRssiValue = String.valueOf(mRssiFilterValue) + getString(R.string.BEACON_RSSI_UINT);
                mTxtViewRssi.setText(strRssiValue);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mTxtViewRssi = (TextView) findViewById(R.id.txtViewRssiValue);
        mEditFltDevName = (EditText) findViewById(R.id.editFilterName);
        mEditFltDevName.addTextChangedListener(new EditChangedListener());
        mBtnRmvNameFilter = (Button)findViewById(R.id.btmRemoveFilterName);

        // Phase 3: 기능 컴포넌트 초기화 (Context 추가)
        // RingManager 제거: BleService로 단일화
        mBatteryScheduler = new BatteryScheduler(mBeaconsMgr, mBeaconDataStore, this);
        
        // 하단 폰 알람 버튼 초기화 및 연결
        mBtnPhoneAlarm = (Button) findViewById(R.id.btn_phone_alarm);
        mBtnPhoneAlarmStop = (Button) findViewById(R.id.btn_phone_alarm_stop);
        
        // Phase 3: 폰 알람 버튼 리스너 연결
        mBtnPhoneAlarm.setOnClickListener(v -> startPhoneAlarm());
        mBtnPhoneAlarmStop.setOnClickListener(v -> stopPhoneAlarm());

        swipeRefreshLayout = (SwipeRefreshLayout)findViewById(R.id.swipe_container);
        //设置刷新时动画的颜色，可以设置4个
        swipeRefreshLayout.setColorSchemeResources(android.R.color.holo_blue_light, android.R.color.holo_red_light, android.R.color.holo_orange_light, android.R.color.holo_green_light);
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {

            @Override
            public void onRefresh() {
                // TODO Auto-generated method stub
                new Handler().postDelayed(new Runnable() {

                    @Override
                    public void run() {
                        // TODO Auto-generated method stub
                        swipeRefreshLayout.setRefreshing(false);
                        if (mScanFailedContinueNum >= MAX_ERROR_SCAN_NUMBER) {
                            mScanFailedContinueNum = 0;
                            new AlertDialog.Builder(DeviceScanActivity.this)
                                    .setTitle(R.string.common_error_title)
                                    .setMessage(R.string.bluetooth_error_need_reboot)
                                    .setPositiveButton(R.string.Dialog_OK, null)
                                    .show();
                        }else{
                            clearAllData();
                            mDevListAdapter.notifyDataSetChanged();
                        }
                    }
                }, 500);
            }
        });
        
        // 크래시 스나이퍼 패치: 권한 + Bluetooth ON 안전 체크
        startBleServiceSafely();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnRemoveAllFilter){
            mBtnFilterTotal.setText("");
            mSeekBarRssi.setProgress(0);
            mEditFltDevName.setText("");
            mBtnRmvAllFilter.setVisibility(View.GONE);
            enableFilterSetting();
        }else if (id == R.id.imageButtonArrow) {
            checkDetailFilterDlg();
        }else if (id == R.id.btnFilterInfo) {
            checkDetailFilterDlg();
        }else if (id == R.id.listview) {
            mBtnRmvAllFilter.setVisibility(View.GONE);
        }else if (id == R.id.btmRemoveFilterName) {
            mEditFltDevName.setText("");
        }
    }


    private void enableFilterSetting()
    {
        //filter
        String strFilterName = mEditFltDevName.getText().toString();
        boolean bChangeFilter = false;
        if (!strFilterName.equals(mBeaconsMgr.getScanNameFilter()))
        {
            mBeaconsMgr.setScanNameFilter(strFilterName, true);
            bChangeFilter = true;
        }
        if (mRssiFilterValue != mBeaconsMgr.getScanMinRssiFilter())
        {
            mBeaconsMgr.setScanMinRssiFilter(mRssiFilterValue);
            bChangeFilter = true;
        }
        if (bChangeFilter){
            clearAllData();
            mDevListAdapter.notifyDataSetChanged();
        }

        //update information
        String strTotalFilter = "";
        if (strFilterName.length() > 0) {
            strTotalFilter = strFilterName + ";";
        }
        if (mRssiFilterValue != -100) {
            strTotalFilter = strTotalFilter + String.valueOf(mRssiFilterValue) + getString(R.string.BEACON_RSSI_UINT);
        }
        mBtnFilterTotal.setText(strTotalFilter);


        //show remove button
        if (strTotalFilter.length() > 0) {
            mBtnRmvAllFilter.setVisibility(View.VISIBLE);
        } else {
            mBtnRmvAllFilter.setVisibility(View.GONE);
        }
    }

    void checkDetailFilterDlg()
    {
        if (mLayoutFilterRssi.getVisibility() == View.GONE) {
            mLayoutFilterRssi.setVisibility(View.VISIBLE);
            mLayoutFilterName.setVisibility(View.VISIBLE);
            mBtnFilterArrow.setBackground(getResources().getDrawable(R.drawable.uparrow));
            mBtnFilterArrow.setTag(1);
        } else {
            mLayoutFilterRssi.setVisibility(View.GONE);
            mLayoutFilterName.setVisibility(View.GONE);
            mBtnFilterArrow.setBackground(getResources().getDrawable(R.drawable.downarrow));
            mBtnFilterArrow.setTag(0);

            enableFilterSetting();
        }
    }


    private class EditChangedListener implements TextWatcher {
        private CharSequence temp;//监听前的文本
        private int editStart;//光标开始位置
        private int editEnd;//光标结束位置
        private final int charMaxNum = 10;

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            temp = s;
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (s.length() > 0)
            {
                mBtnRmvNameFilter.setVisibility(View.VISIBLE);
            }else{
                mBtnRmvNameFilter.setVisibility(View.GONE);
            }
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    public void clearAllData()
    {
        mBeaconsDictory.clear();
        mBeaconsArray = null;
        mBeaconsMgr.clearBeacons();
    }

    // onBeaconDiscovered 제거됨 - 이제 BleService가 delegate 독점

    public void example_printAllAdvPackets(KBeacon[] beacons)
    {
        for (KBeacon beacon : beacons) {
            //get beacon adv common info
            Log.v(LOG_TAG, "beacon mac:" + beacon.getMac());
            Log.v(LOG_TAG, "beacon name:" + beacon.getName());
            Log.v(LOG_TAG, "beacon rssi:" + beacon.getRssi());

            //get adv packet
            for (KBAdvPacketBase advPacket : beacon.allAdvPackets()) {
                switch (advPacket.getAdvType()) {
                    // iBeacon case removed - KBeacon protocol only

                    case KBAdvType.EddyTLM: {
                        KBAdvPacketEddyTLM advTLM = (KBAdvPacketEddyTLM) advPacket;
                        Log.v(LOG_TAG, "TLM battery:" + advTLM.getBatteryLevel());
                        Log.v(LOG_TAG, "TLM Temperature:" + advTLM.getTemperature());
                        Log.v(LOG_TAG, "TLM adv count:" + advTLM.getAdvCount());
                        break;
                    }

                    case KBAdvType.Sensor: {
                        KBAdvPacketSensor advSensor = (KBAdvPacketSensor) advPacket;
                        Log.v(LOG_TAG, "Sensor battery:" + advSensor.getBatteryLevel());
                        Log.v(LOG_TAG, "Sensor temp:" + advSensor.getTemperature());

                        //device that has acc sensor
                        KBAccSensorValue accPos = advSensor.getAccSensor();
                        if (accPos != null) {
                            String strAccValue = String.format(Locale.ENGLISH, "x:%d; y:%d; z:%d",
                                    accPos.xAis, accPos.yAis, accPos.zAis);
                            Log.v(LOG_TAG, "Sensor Acc:" + strAccValue);
                        }

                        //device that has humidity sensor
                        if (advSensor.getHumidity() != null) {
                            Log.v(LOG_TAG, "Sensor humidity:" + advSensor.getHumidity());
                        }

                        //device that has alarm sensor(cutoff, door, parking sensor)
                        if (advSensor.getAlarmStatus() != null) {
                            Log.v(LOG_TAG, "alarm flag:" + advSensor.getAlarmStatus());
                        }

                        //device that has PIR sensor
                        if (advSensor.getPirIndication() != null) {
                            Log.v(LOG_TAG, "pir indication:" + advSensor.getPirIndication());
                        }

                        //device that has light sensor
                        if (advSensor.getLuxValue() != null) {
                            Log.v(LOG_TAG, "light level:" + advSensor.getLuxValue());
                        }
                        break;
                    }

                    case KBAdvType.EddyUID: {
                        KBAdvPacketEddyUID advUID = (KBAdvPacketEddyUID) advPacket;
                        Log.v(LOG_TAG, "UID Nid:" + advUID.getNid());
                        Log.v(LOG_TAG, "UID Sid:" + advUID.getSid());
                        break;
                    }

                    case KBAdvType.EddyURL: {
                        KBAdvPacketEddyURL advURL = (KBAdvPacketEddyURL) advPacket;
                        Log.v(LOG_TAG, "URL:" + advURL.getUrl());
                        break;
                    }

                    case KBAdvType.System: {
                        KBAdvPacketSystem advSystem = (KBAdvPacketSystem) advPacket;
                        Log.v(LOG_TAG, "System mac:" + advSystem.getMacAddress());
                        Log.v(LOG_TAG, "System model:" + advSystem.getModel());
                        Log.v(LOG_TAG, "System batt:" + advSystem.getBatteryPercent());
                        Log.v(LOG_TAG, "System ver:" + advSystem.getVersion());
                        break;
                    }

                    //encrypt beacon
                    case KBAdvType.EBeacon: {
                        KBAdvPacketEBeacon encryptAdv = (KBAdvPacketEBeacon) advPacket;
                        Log.v(LOG_TAG, "System mac:" + encryptAdv.getMac());
                        Log.v(LOG_TAG, "Decrypt UUID:" + encryptAdv.getUuid());
                        Log.v(LOG_TAG, "ADV UTC:" + encryptAdv.getUtcSecCount());
                        Log.v(LOG_TAG, "Reference power:" + encryptAdv.getRefTxPower());
                        break;
                    }

                    default:
                        break;
                }
            }
        }
    }

    // onCentralBleStateChang, onScanFailed 제거됨 - 이제 BleService가 delegate 독점

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_scan) {
            // 스캔 제어를 Service로 일원화
            if (mServiceBound && mBleService != null) {
                mBleService.startScanning();
                Log.d(TAG, "Start scanning via BleService");
            } else {
                Log.w(TAG, "BleService not bound, starting service");
                startBleServiceSafely();
            }
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.menu_stop) {
            // 스캔 중지를 Service로 일원화
            if (mServiceBound && mBleService != null) {
                mBleService.stopScanning();
                Log.d(TAG, "Stop scanning via BleService");
            }
            invalidateOptionsMenu();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

        Log.e(TAG, "click id:" + id );
        KBeacon beacon = getBeaconDevice(position);
        if (beacon != null) {
            // 단일 화면 전환: DevicePannelActivity 대신 이름 변경 다이얼로그 표시
            showDeviceNameChangeDialog(beacon);
        }
    }
    
    // Phase 4B: 이름 변경 다이얼로그 구현
    private void showDeviceNameChangeDialog(KBeacon beacon) {
        if (beacon == null) {
            return;
        }
        
        String mac = beacon.getMac();
        String currentName = beacon.getName();
        
        // [A] 별엀 기준 일관화: 다이얼로그에 전달할 이름 조회
        BeaconState beaconState = mBeaconDataStore.get(mac);
        String displayName = "Unknown";
        if (beaconState != null) {
            displayName = beaconState.getDisplayName(); // 별칭 우선, 없으면 광고 이름
        }
        
        showDeviceNameChangeDialog(mac);
    }
    
    /**
     * 비콘 이름 변경 다이얼로그
     * @param mac 비콘 MAC 주소
     * @param currentName 현재 이름
     */
    // [수정2] 별칭 우선 다이얼로그 초기화
    private void showDeviceNameChangeDialog(@NonNull String mac) {
        // alias → name → "" 순서로 초기값 설정
        String alias = mPrefs.getAlias(mac);
        BeaconState st = mBeaconDataStore.get(mac);
        String initial = !android.text.TextUtils.isEmpty(alias)
                ? alias
                : (st != null && !android.text.TextUtils.isEmpty(st.getName()) ? st.getName() : "");
        
        Log.d("NAME", "showDeviceNameChangeDialog mac=" + mac + " initial=" + initial);
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("별칭 설정"); // 별칭 용어 사용으로 혼동 방지
        
        // EditText 설정
        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setText(initial);
        if (!android.text.TextUtils.isEmpty(initial)) {
            editText.setSelection(initial.length()); // 커서를 끝으로
        }
        editText.setHint("별칭 입력 (1-18자)"); // 별칭 용어로 일관화
        editText.setSingleLine(true);
        editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(18)});
        
        // 다이얼로그에 EditText 추가
        builder.setView(editText);
        
        // 확인 버튼
        builder.setPositiveButton("확인", (dialog, which) -> {
            String newName = editText.getText().toString().trim();
            
            // 유효성 검사
            if (newName.isEmpty()) {
                toastShow("별칭을 입력해주세요");
                return;
            }
            
            if (newName.length() > 18) {
                toastShow("별칭은 18자 이하로 입력해주세요");
                return;
            }
            
            // 제어문자 및 특수문자 검사 (기본적인 검증)
            if (newName.matches(".*[\\p{Cntrl}].*")) {
                toastShow("제어문자는 사용할 수 없습니다");
                return;
            }
            
            // [수정2] 별칭 저장 및 즉시 반영
            String oldAlias = mPrefs.getAlias(mac);
            String oldDisplay = !android.text.TextUtils.isEmpty(oldAlias) ? oldAlias : "";
            
            mPrefs.setAlias(mac, newName);
            if (st != null) {
                st.setAliasName(newName);
            }
            
            // 즉시 반영
            if (mDevListAdapter != null) {
                mDevListAdapter.notifyDataSetChanged();
            }
            
            Log.d("NAME", "alias change: mac=" + mac + " old=" + oldDisplay + " new=" + newName);
            Toast.makeText(this, "별칭 변경: " + oldDisplay + " → " + newName, Toast.LENGTH_SHORT).show();
        });
        
        // 취소 버튼
        builder.setNegativeButton("취소", (dialog, which) -> dialog.dismiss());
        
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    /**
     * 별칭을 BeaconState와 Prefs에 저장 (광고 이름과 분리)
     * @param mac 비콘 MAC 주소
     * @param alias 새 별칭
     */
    private void saveAlias(String mac, String alias) {
        // Prefs에 별칭 저장
        mPrefs.setAlias(mac, alias);
        
        // BeaconState 업데이트
        BeaconState beaconState = mBeaconDataStore.get(mac);
        if (beaconState != null) {
            beaconState.setAliasName(alias);
            mBeaconDataStore.upsert(beaconState);
        } else {
            // 새로운 BeaconState 생성 (일반적으로는 발생하지 않음)
            beaconState = new BeaconState("Unknown", mac);
            beaconState.setAliasName(alias);
            mBeaconDataStore.upsert(beaconState);
        }
        
        // 별칭 저장 완료 로깅
        Log.d("ALIAS", "Saved alias for MAC: " + mac + " -> " + alias);
        
        // UI는 500ms 주기 갱신에서 자동으로 반영됨 (별도 invalidate 불필요)
    }


    /**
     * [D1] 6자리 숫자 파싱 유틸 (정렬용)
     * @param s displayName
     * @return 선두 6자리 숫자 (없으면 Integer.MAX_VALUE)
     */
    private static Integer leadingSix(String s) {
        if (s == null) return Integer.MAX_VALUE;
        Matcher m = Pattern.compile("^(\\d{6})").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /**
     * [D1] 500ms 주기로 BeaconDataStore에서 데이터를 가져와 정렬 후 UI 갱신
     */
    private void updateUiFromDataStore() {
        try {
            List<BeaconState> beaconStates = mBeaconDataStore.getValidBeacons();
            Log.v("UI_UPDATE", String.format("updateUiFromDataStore: beacons=%d", beaconStates.size()));
            
            // [D1] 500ms 렌더 전 스냅샷 정렬: (6자리, displayName, mac) 순
            beaconStates.sort((a, b) -> {
                // 1순위: displayName 선두 6자리 숫자
                Integer sixA = leadingSix(a.getDisplayName());
                Integer sixB = leadingSix(b.getDisplayName());
                int compare = sixA.compareTo(sixB);
                if (compare != 0) return compare;
                
                // 2순위: displayName 사전순
                String nameA = a.getDisplayName() != null ? a.getDisplayName() : "";
                String nameB = b.getDisplayName() != null ? b.getDisplayName() : "";
                compare = nameA.compareToIgnoreCase(nameB);
                if (compare != 0) return compare;
                
                // 3순위: MAC 대문자 사전순
                String macA = a.getMac() != null ? a.getMac().toUpperCase() : "";
                String macB = b.getMac() != null ? b.getMac().toUpperCase() : "";
                return macA.compareTo(macB);
            });
            
            mDevListAdapter.updateBeaconStates(beaconStates);
            mDevListAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            Log.d(TAG, "Error updating UI from data store: " + e.getMessage());
        }
    }
    
    /**
     * BleService에서 필터링된 비콘 목록을 받아와 UI 업데이트
     * - Service가 이름 필터링과 정렬을 모두 담당
     * - Activity는 UI 업데이트만 담당
     */
    private void updateUiFromService() {
        try {
            if (!mServiceBound || mBleService == null) {
                Log.v("UI_UPDATE", "Service not bound, skipping UI update");
                return;
            }
            
            List<BeaconState> filteredBeacons = mBleService.getFilteredBeaconStates();
            // DataStore도 동기화하여 캘리브레이션/별칭 조회가 Unknown으로 떨어지지 않게 함
            if (mBeaconDataStore != null && filteredBeacons != null) {
                for (BeaconState s : filteredBeacons) {
                    if (s != null && s.getMac() != null) {
                        mBeaconDataStore.upsert(s);
                    }
                }
            }
            
            // FORCE LOG: UI 업데이트 디버깅
            Log.e(TAG, "FORCE LOG: UI UPDATE - Found " + filteredBeacons.size() + " beacons");
            for (BeaconState state : filteredBeacons) {
                String displayName = state.getDisplayName();
                String advName = state.getAdvertisedName();
                Log.e(TAG, "FORCE LOG: UI Beacon - display: " + displayName + 
                          ", adv: " + advName + ", MAC: " + state.getMac());
            }
            
            Log.v("UI_UPDATE", String.format("updateUiFromService: beacons=%d", filteredBeacons.size()));
            
            // [패치 D] 스로틀링 적용 - 빈번한 갱신 시 병합
            long now = System.currentTimeMillis();
            if (now - lastUiUpdateTime >= UI_UPDATE_THROTTLE_MS) {
                mDevListAdapter.updateBeaconStates(filteredBeacons);
                mDevListAdapter.notifyDataSetChanged();
                lastUiUpdateTime = now;
                Log.v(TAG, "UI updated and throttle timestamp updated");
            } else {
                Log.v(TAG, "UI update throttled - too frequent");
            }
        } catch (Exception e) {
            Log.d(TAG, "Error updating UI from service: " + e.getMessage());
        }
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        // 최초 진입/복귀 시 권한 체크 + 서비스 바인드/시작
        startBleServiceSafely();
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // 500ms UI 갱신 시작
        mUiUpdateHandler.post(mUiUpdateRunnable);
        
        // 브로드캐스트 리시버 등록
        maybeRegisterServiceReceiver();
    }

    @Override
    protected void onPause() {
        super.onPause();
        
        // 500ms UI 갱신 중지
        mUiUpdateHandler.removeCallbacks(mUiUpdateRunnable);
        
        // 브로드캐스트 리시버 해제
        maybeUnregisterServiceReceiver();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // 스캔 제어를 Service에 완전히 위임 - Activity가 직접 중지하지 않음
        // BleService가 백그라운드에서 계속 실행되어야 함
        Log.d(TAG, "onStop - Activity가 백그라운드로 이동, 스캔 유지");
        invalidateOptionsMenu();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // onPause에서 이미 해제되므로 여기서는 이중 해제 방지
        
        // BleService 바인딩 해제
        if (mServiceBound) {
            try {
                unbindService(mServiceConnection);
                mServiceBound = false;
            } catch (Exception e) {
                Log.w(TAG, "Failed to unbind service", e);
            }
        }

        // Phase 3: 기능 컴포넌트 정리
        // RingManager 제거: BleService로 단일화
        if (mBatteryScheduler != null) {
            mBatteryScheduler.shutdown();
        }
        stopPhoneAlarm();

        if (mBeaconsMgr != null) {
            mBeaconsMgr.clearBeacons();
        }
    }
    
    private void maybeRegisterServiceReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleService.ACTION_BEACON_UPDATE);
        filter.addAction(BleService.ACTION_SCAN_STATE_CHANGED);
        filter.addAction(BleService.ACTION_RING_STATE_CHANGED);
        filter.addAction(BleService.ACTION_AUTO_ALARM_TRIGGERED);
        filter.addAction(BleService.ACTION_SCAN_NO_RESULTS);
        filter.addAction(BleService.ACTION_TOAST);
        // 캘리브레이션 관련 브로드캐스트
        filter.addAction(BleService.ACTION_CALIBRATION_SAMPLE);
        filter.addAction(BleService.ACTION_CALIB_RSSI_SAMPLE);
        filter.addAction(BleService.ACTION_CALIB_STAGE_COMPLETE);
        filter.addAction(BleService.ACTION_CALIB_STAGE_STARTED);
        filter.addAction(BleService.ACTION_CALIBRATION_ERROR);
        filter.addAction("com.kkmcn.sensordemo.NEED_PERMISSIONS");
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceBroadcastReceiver, filter);
        receiverRegistered = true;
        Log.d(TAG, "Service receiver registered");
    }

    private void maybeUnregisterServiceReceiver() {
        if (!receiverRegistered) return;
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceBroadcastReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister broadcast receiver", e);
        } finally {
            receiverRegistered = false;
            Log.d(TAG, "Service receiver unregistered");
        }
    }

    private void handlePeriodChk(){
        long currTick = System.currentTimeMillis();
    }

    private void handleStartScan(){
        // 권한 체크 및 스캔은 BleService로 완전히 위임
        if (mServiceBound && mBleService != null) {
            boolean success = mBleService.startScanning();
            if (success) {
                Log.v(TAG, "start scan success via BleService");
            } else {
                toastShow("Failed to start BLE scanning");
            }
        } else {
            Log.w(TAG, "BleService not bound, starting service");
            startBleServiceSafely();
        }
    }

    private boolean checkBluetoothPermitAllowed() {
        boolean ok = true;

        if (Build.VERSION.SDK_INT >= 31) {          // Android 12+
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN}, PERMISSION_SCAN);
                ok = false;
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_CONNECT);
                ok = false;
            }
            // Samsung/일부 제조사에서는 Android 12+에서도 위치 권한 필요할 수 있음
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_FINE_LOCATION);
                ok = false;
            }
        } else {                                     // Android 10/11 이하
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_FINE_LOCATION);
                ok = false;
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_COARSE_LOCATION);
                ok = false;
            }
        }
        return ok;
    }
    
    /**
     * 크래시 방지: Android 버전별 필수 권한 배열 반환
     * @return 필수 권한 배열
     */
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+
            // ★ 일부 단말 호환을 위해 FINE_LOCATION도 같이 요청(필요 없으면 주석처리 가능)
            return new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            // ★ 삼성 등 제조사 호환: 초기부터 FINE_LOCATION도 같이 묻기
            return new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else { // Android 10/11
            return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
    }
    
    /**
     * 크래시 방지: 모든 필수 권한이 승인되었는지 확인
     * @return true if 모든 권한 승인됨
     */
    private boolean hasAllRequiredPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        return true;
    }
    
    /**
     * 크래시 스나이퍼 패치: Bluetooth ON 체크
     * @return true if Bluetooth 활성화됨
     */
    private boolean isBtEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }
    
    /**
     * 크래시 스나이퍼 패치: 권한 + Bluetooth ON 안전 체크 후 Service 시작
     */
    private void startBleServiceSafely() {
        Log.d(TAG, "startBleServiceSafely called");
        
        // 1) 권한 확인
        if (!hasAllRequiredPermissions()) {
            Log.w(TAG, "Missing permissions, requesting...");
            requestPermsOnce(getRequiredPermissions(), REQ_PERMS);
            return;
        }
        
        // 2) 블루투스 ON 확인 (OFF면 먼저 요청)
        if (!isBtEnabled()) {
            Log.w(TAG, "Bluetooth OFF, requesting enable...");
            Intent btIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            // Android 12+ BLUETOOTH_CONNECT 권한 확인
            if (Build.VERSION.SDK_INT >= 31) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                    startActivityForResult(btIntent, REQ_BT_ON);
                } else {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission required for BT enable intent");
                    // 권한 없으면 그냥 진행 (사용자가 수동으로 BT 켜야 함)
                }
            } else {
                startActivityForResult(btIntent, REQ_BT_ON);
            }
            return;
        }
        
        // 3) 한 프레임 늦춰서 시작(권한 다이얼로그 복귀 타이밍 레이스 방지)
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Log.i(TAG, "Starting BleService with all safety checks passed");
                
                Intent serviceIntent = new Intent(this, BleService.class);
                ContextCompat.startForegroundService(this, serviceIntent);
                bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
                
                // 리시버는 onResume 시 단 한 번만 등록
                maybeRegisterServiceReceiver();
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to start BleService safely", e);
                Toast.makeText(this, "서비스 시작 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 크래시 스나이퍼 패치: Bluetooth 활성화 결과 처리
        if (requestCode == REQ_BT_ON) {
            if (isBtEnabled()) {
                Log.i(TAG, "Bluetooth enabled, retrying safe service start");
                startBleServiceSafely();
            } else {
                Log.w(TAG, "Bluetooth enable denied");
                Toast.makeText(this, "블루투스를 켜야 합니다.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * 권한 요청 디바운스 함수 (중복 요청 방지)
     */
    private void requestPermsOnce(String[] perms, int reqCode) {
        if (askingRuntimePerms) {
            Log.d(TAG, "Already asking permissions, ignoring duplicate request");
            return;
        }
        askingRuntimePerms = true;
        ActivityCompat.requestPermissions(this, perms, reqCode);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        // 반드시 디바운스 플래그 해제
        askingRuntimePerms = false;

        // 크래시 스나이퍼 패치: 권한 승인 후 안전 재체크
        if (requestCode == REQ_PERMS) {
            startBleServiceSafely(); // 권한 상태 다시 체크 + BT ON 확인
            return;
        }

        // 기존 개별 권한 처리 (backward compatibility)
        if (requestCode == PERMISSION_SCAN){
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED){
                toastShow("The app need ble scanning permission for start ble scanning");
            }
        }

        if (requestCode == PERMISSION_CONNECT){
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED){
                toastShow("The app need ble connecting permission for ble finding");
            }
        }

        if (requestCode == PERMISSION_COARSE_LOCATION){
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED){
                toastShow("The app need coarse location permission for start ble scanning");
            }
        }
        if (requestCode == PERMISSION_FINE_LOCATION){
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "FINE_LOCATION result: " + granted);
            // 권한 허용 직후 스캔 재시작
            if (granted) {
                if (mServiceBound && mBleService != null) {
                    mBleService.stopScanning();
                    mBleService.startScanning();
                } else {
                    startBleServiceSafely();
                }
            } else {
                toastShow("The app need fine location permission for start ble scanning");
            }
        }
    }

    public KBeacon getBeaconDevice(int nIndex)
    {
        if (mBeaconsArray != null && mBeaconsArray.length > nIndex)
        {
            return mBeaconsArray[nIndex];
        }
        else
        {
            return null;
        }
    }

    public int getCount()
    {
        if (mBeaconsArray == null){
            return 0;
        }else{
            return mBeaconsArray.length;
        }
    }
    
    // Phase 2 Part 3: SharedPreferences 저장 훅 메서드들
    // (UI 핸들러에서 설정값 변경 시 호출됨)
    
    /**
     * 거리 임계값 저장 훅
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     * @param value 거리 임계값 (미터)
     */
    public void saveDistanceThreshold(String mac, String name, double value) {
        if (mPrefs != null) {
            mPrefs.setDistanceThreshold(mac, name, value);
            Log.d(TAG, "Saved distance threshold: " + name + " = " + value + " m");
        }
        // TODO Phase 3: BeaconState의 임계값도 동시 업데이트
    }
    
    /**
     * 캘리브레이션 결과 저장 훅
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     * @param txPowerAt1m 1미터 기준 송신 파워 (dBm)
     * @param pathLossExponent 경로손실지수
     */
    public void saveCalibration(String mac, String name, double txPowerAt1m, double pathLossExponent) {
        if (mPrefs != null) {
            mPrefs.setTxPowerAt1m(mac, name, txPowerAt1m);
            mPrefs.setN(mac, name, pathLossExponent);
            Log.d(TAG, "Saved calibration: " + name + " txPower=" + txPowerAt1m + ", n=" + pathLossExponent);
        }
        // TODO Phase 3: 기존 DistanceEstimator 갱신 (새로운 캘리브레이션 값 적용)
    }
    
    /**
     * 배터리 상태 저장 훅
     * @param mac 비콘 MAC 주소
     * @param name 비콘 이름
     * @param batteryPct 배터리 % (0-100)
     */
    public void saveBatteryPct(String mac, String name, int batteryPct) {
        if (mPrefs != null) {
            mPrefs.setBatteryPct(mac, name, batteryPct);
            Log.d(TAG, "Saved battery: " + name + " = " + batteryPct + "%");
        }
    }
    
    // Phase 3: LeDeviceListAdapter.OnRowActionListener 콜백 구현
    
    // [수정2] 별칭 우선 다이얼로그 초기화 (currentName 사용 안함)
    @Override
    public void onNameEdit(String mac, String currentName) {
        Log.d("NAME", "onNameEdit mac=" + mac);
        showDeviceNameChangeDialog(mac);
    }
    
    @Override
    public void onRingStart(String mac) {
        // [캘리브레이션] 가드: 캘리브레이션 중에는 수동 알람 비활성
        if (isCalibrating) {
            Log.d(TAG, "Ring alarm blocked - calibration in progress");
            return;
        }
        
        Log.d("RING", String.format("UI onRingStart: MAC=%s", mac));
        
        // 즉시 버튼 상태 업데이트 (사용자 피드백)
        updateButtonState(mac, "동작중");
        
        // [터치디바운스] 클릭 직후 100ms 프리즈로 리스너 재설정 레이스 추가 차단 (250ms → 100ms 단축)
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 100;
        
        // Command Gate 패턴: 플래그만 설정, 실제 명령은 게이트에서 처리
        if (mServiceBound && mBleService != null) {
            mBleService.setDesiredRingPublic(mac, true, BleService.RingReason.MANUAL_START);
            Log.i("RING", "BleService.setDesiredRingPublic(true) called for MAC: " + mac);
        } else {
            // 서비스 준비 중 - UI 피드백만 제공, 명령 호출 금지
            Log.w("RING", "Service not bound - waiting for service initialization");
            updateButtonState(mac, "서비스 준비 중");
        }
    }
    
    @Override
    public void onRingStop(String mac) {
        // [캘리브레이션] 가드: 캘리브레이션 중에는 수동 알람 비활성
        if (isCalibrating) {
            Log.d(TAG, "Ring stop blocked - calibration in progress");
            return;
        }
        
        // 즉시 버튼 상태 업데이트 (사용자 피드백)
        updateButtonState(mac, "동작중");
        
        Log.d("RING", String.format("UI onRingStop: MAC=%s", mac));
        
        // [터치디바운스] 클릭 직후 100ms 프리즈로 리스너 재설정 레이스 추가 차단 (250ms → 100ms 단축)
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 100;
        
        // 즉시 STOP 처리: 모든 대기 명령보다 우선
        if (mServiceBound && mBleService != null) {
            mBleService.setDesiredRingPublic(mac, false, BleService.RingReason.MANUAL_STOP);
            Log.i("RING", "BleService.setDesiredRingPublic(false) called for MAC: " + mac);
        } else {
            // 서비스 준비 중 - UI 피드백만 제공, 명령 호출 금지
            Log.w("RING", "Service not bound - waiting for service initialization");
            updateButtonState(mac, "서비스 준비 중");
        }
    }
    
    @Override
    public void onDistanceSetting(String mac) {
        Log.d(TAG, "Distance setting requested for MAC: " + mac);
        
        // [터치디바운스] 다이얼로그 띄우는 동안 UI 갱신 금지
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 100;
        
        // 서비스 바인더를 통한 통일된 거리 조회
        float currentThreshold = 50.0f; // 기본값
        if (mServiceBound && mBleService != null) {
            currentThreshold = mBleService.getDistanceThreshold(mac);
        }
        
        BeaconState beaconState = mBeaconDataStore.get(mac);
        String displayName = (beaconState != null) ? 
            beaconState.getDisplayName() : "Unknown"; // 별칭 우선 사용
            
        showDistanceSettingDialog(mac, displayName, currentThreshold);
    }
    
    /**
     * Phase 4C: 거리 임계값 설정 다이얼로그
     * @param mac 비콘 MAC 주소
     * @param beaconName 비콘 이름
     * @param currentThreshold 현재 임계값 (미터)
     */
    private void showDistanceSettingDialog(String mac, String beaconName, double currentThreshold) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("거리 임계값 설정 - " + beaconName); // 깔끔한 제목
        
        // EditText 설정
        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setText(String.format("%.1f", currentThreshold));
        editText.setSelection(editText.getText().length()); // 커서를 끝으로
        editText.setHint("거리 입력 (0.5 ~ 200.0m)");
        editText.setSingleLine(true);
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        
        // 다이얼로그에 EditText 추가
        builder.setView(editText);
        
        // 확인 버튼
        builder.setPositiveButton("확인", (dialog, which) -> {
            String distanceStr = editText.getText().toString().trim();
            
            // 유효성 검사
            if (distanceStr.isEmpty()) {
                toastShow("거리를 입력해주세요");
                return;
            }
            
            double newThreshold;
            try {
                newThreshold = Double.parseDouble(distanceStr);
            } catch (NumberFormatException e) {
                toastShow("올바른 숫자를 입력해주세요");
                return;
            }
            
            // 범위 검사 (0.5m ~ 200.0m)
            if (newThreshold < 0.5 || newThreshold > 200.0) {
                toastShow("거리는 0.5m ~ 200.0m 범위로 입력해주세요");
                return;
            }
            
            // 서비스 바인더를 통한 통일된 저장
            if (mServiceBound && mBleService != null) {
                mBleService.saveDistanceThreshold(mac, (float)newThreshold);
            } else {
                // 폴백: DevicePrefs에 직접 저장
                com.kkmcn.sensordemo.prefs.DevicePrefs.setDistanceThreshold(getApplicationContext(), mac, (float)newThreshold);
            }
            
            // BeaconState 즉시 갱신 (UI 반영용)
            BeaconState beaconState = mBeaconDataStore.get(mac);
            if (beaconState != null) {
                beaconState.setDistanceThreshold(newThreshold);
                mBeaconDataStore.upsert(beaconState);
            }
            
            Log.d(TAG, "Distance threshold saved: " + beaconName + " = " + newThreshold + "m");
            toastShow("거리 임계값 " + String.format("%.1f", newThreshold) + "m로 저장됨");
        });
        
        // 취소 버튼
        builder.setNegativeButton("취소", (dialog, which) -> dialog.dismiss());
        
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    @Override
    public void onCalibration(String mac) {
        Log.d(TAG, "Calibration requested for MAC: " + mac);
        
        // [터치디바운스] 캘리브레이션 동안 UI 갱신 금지
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 100;
        
        // 캘리브레이션 다이얼로그 표시
        CalibrationDialog.show(this, mac, new CalibrationDialog.CalibrationCallback() {
            @Override
            public void onCalibrationStarted() {
                // 1) BT ON 확인
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                if (ba == null || !ba.isEnabled()) {
                    toastShow("블루투스가 꺼져 있습니다. 켜고 다시 시도하세요.");
                    isCalibrating = false;
                    return;
                }

                // 2) Android 12+ 권한 체크 (BLUETOOTH_SCAN)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(DeviceScanActivity.this, Manifest.permission.BLUETOOTH_SCAN)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermsOnce(new String[]{Manifest.permission.BLUETOOTH_SCAN}, PERMISSION_SCAN);
                        isCalibrating = false;
                        return;
                    }
                } else {
                    // 위치 권한 필요(스캔용)
                    if (ContextCompat.checkSelfPermission(DeviceScanActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermsOnce(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_FINE_LOCATION);
                        isCalibrating = false;
                        return;
                    }
                }

                // 3) Service 바인드/널 가드
                if (!(mServiceBound && mBleService != null)) {
                    toastShow("서비스 연결 대기 중입니다. 잠시 후 다시 시도하세요.");
                    isCalibrating = false;
                    return;
                }

                // 4) OK → 캘리브레이션 모드로 진입
                isCalibrating = true;
                Log.i(TAG, "Calibration started - alarm guard activated");
                
                // BleService에 캘리브레이션 모드 설정 (3중 게이트 활성화)
                mBleService.setCalibrationMode(true, mac);
                
                // ★ UI 즉시 업데이트: 캘리브레이션 시작 상태 표시
                runOnUiThread(() -> {
                    BeaconState beaconState = mBeaconDataStore.get(mac);
                    if (beaconState != null) {
                        beaconState.setCalibrationInProgress(true);
                        beaconState.setCalibrationStage(0); // 준비 단계
                        if (mDevListAdapter != null) {
                            mDevListAdapter.notifyDataSetChanged();
                        }
                    }
                });
            }
            
            @Override
            public void onCalibrationFinished(boolean saved) {
                isCalibrating = false;
                activeCalibrationSession = null;
                
                // BleService의 캘리브레이션 모드 해제 (3중 게이트 비활성화)
                if (mServiceBound && mBleService != null) {
                    mBleService.setCalibrationMode(false, null);
                    mBleService.clearCalibrationTarget();
                }
                
                // ★ UI 즉시 업데이트: 캘리브레이션 완료 상태 표시
                runOnUiThread(() -> {
                    BeaconState beaconState = mBeaconDataStore.get(mac);
                    if (beaconState != null) {
                        beaconState.setCalibrationInProgress(false);
                        if (mDevListAdapter != null) {
                            mDevListAdapter.notifyDataSetChanged();
                        }
                    }
                });
                
                Log.i(TAG, "Calibration finished - alarm guard deactivated, saved=" + saved);
            }
            
            @Override
            public void onSampleNeeded(CalibrationSession session) {
                activeCalibrationSession = session;
                
                // 캘리브레이션 시작 시 타겟 MAC 설정 (RSSI 샘플 브로드캐스트 활성화)
                if (mServiceBound && mBleService != null && session != null) {
                    String targetMac = session.getMac();
                    mBleService.setCalibrationTarget(targetMac);
                    Log.d(TAG, "Calibration target set: " + targetMac);
                }
            }
            
            @Override
            public void onNativeCalibrationScanStart(String mac) {
                // 네이티브 캘리브레이션 스캔 시작
                if (mServiceBound && mBleService != null) {
                    mBleService.startCalibrationScan(mac);
                    Log.i(TAG, "Started native calibration scan for: " + mac);
                }
            }
            
            @Override
            public void onStageCompleted(String mac, int stageIndex, double medianRssi, int keptSamples) {
                Log.d(TAG, String.format("Stage %d completed: MAC=%s, medianRssi=%.1f, samples=%d", 
                       stageIndex + 1, mac, medianRssi, keptSamples));
                
                // BleService로 단계 완료 브로드캐스트 신호 전달
                if (mServiceBound && mBleService != null) {
                    mBleService.broadcastCalibrationStageCompleted(mac, stageIndex, medianRssi, keptSamples);
                }
                
                // ★ UI 즉시 업데이트: 단계 완료 상태 표시
                runOnUiThread(() -> {
                    BeaconState beaconState = mBeaconDataStore.get(mac);
                    if (beaconState != null) {
                        beaconState.setCalibrationStage(stageIndex + 1); // 1-based index
                        // 마지막 단계(3단계) 완료 시에만 캘리브레이션 진행 플래그를 비활성화
                        if (stageIndex >= 2) { // 0-based index, so 2 = stage 3
                            beaconState.setCalibrationInProgress(false);
                        }
                        if (mDevListAdapter != null) {
                            mDevListAdapter.notifyDataSetChanged();
                        }
                    }
                });
            }
            
            @Override
            public void onStageStarted(String mac, int stageIndex, double distanceMeters) {
                Log.d(TAG, String.format("Stage %d started: MAC=%s, distance=%.1fm", 
                       stageIndex + 1, mac, distanceMeters));
                
                // BleService로 단계 시작 브로드캐스트 신호 전달
                if (mServiceBound && mBleService != null) {
                    mBleService.broadcastCalibrationStageStartedPublic(mac, stageIndex, distanceMeters);
                }
                
                // ★ UI 즉시 업데이트: 캘리브레이션 진행 상태 표시
                runOnUiThread(() -> {
                    BeaconState beaconState = mBeaconDataStore.get(mac);
                    if (beaconState != null) {
                        beaconState.setCalibrationInProgress(true);
                        beaconState.setCalibrationStage(stageIndex + 1); // 1-based index
                        if (mDevListAdapter != null) {
                            mDevListAdapter.notifyDataSetChanged();
                        }
                    }
                });
            }
            
            @Override
            public void onNativeCalibrationScanStop() {
                // 네이티브 캘리브레이션 스캔 중지
                if (mServiceBound && mBleService != null) {
                    mBleService.stopCalibrationScan();
                    Log.i(TAG, "Stopped native calibration scan");
                }
            }
            
            @Override
            public String getBeaconDisplayName(String mac) {
                BeaconState beaconState = mBeaconDataStore.get(mac);
                return (beaconState != null) ? beaconState.getDisplayName() : "Unknown";
            }
            
            @Override
            public void saveCalibrationResult(String mac, CalibrationSession.CalibrationResult result) {
                Log.e(TAG, "★★★ [CALLBACK-TRACE] saveCalibrationResult CALLED ★★★");
                Log.e(TAG, String.format(Locale.US, "[CALLBACK-TRACE] MAC=%s, tx1m=%.2f, n=%.2f, R²=%.2f", 
                       mac, result.txPowerAt1m, result.pathLossExponent, result.rSquared));
                
                // 1. Prefs에 캘리브레이션 결과 저장
                mPrefs.saveCalibration(mac, result.txPowerAt1m, result.pathLossExponent, 
                                     result.rSquared, result.rmse, result.maxResidual, result.timestampMs);
                Log.e(TAG, "[CALLBACK-TRACE] Step 1: Saved to preferences");
                
                // 2. BeaconState 업데이트
                BeaconState beaconState = mBeaconDataStore.get(mac);
                if (beaconState != null) {
                    beaconState.setTxPowerAt1m(result.txPowerAt1m);
                    beaconState.setPathLossExponent(result.pathLossExponent);
                    Log.e(TAG, "[CALLBACK-TRACE] Step 2: Updated BeaconState");
                } else {
                    Log.e(TAG, "[CALLBACK-TRACE] Step 2: BeaconState NOT FOUND for MAC: " + mac);
                }
                
                // 3. DistanceEstimator에 새 캘리브레이션 적용
                DistanceEstimator estimator = mDistanceEstimators.get(mac);
                if (estimator != null) {
                    estimator.setCalibration(result.txPowerAt1m, result.pathLossExponent);
                    Log.e(TAG, String.format(Locale.US, "[CALLBACK-TRACE] Step 3: Applied calibration to estimator: MAC=%s, tx1m=%.2f, n=%.2f", 
                           mac, result.txPowerAt1m, result.pathLossExponent));
                } else {
                    Log.e(TAG, "[CALLBACK-TRACE] Step 3: DistanceEstimator NOT FOUND for MAC: " + mac);
                }
                
                // ★ 4. BleService에 캘리브레이션 저장 및 거리 재계산 요청
                if (mServiceBound && mBleService != null) {
                    Log.e(TAG, String.format(Locale.US, "[CALLBACK-TRACE] Step 4: Calling BleService.saveCalibrationResultToPrefs: MAC=%s, tx1m=%.5f, n=%.5f", 
                           mac, result.txPowerAt1m, result.pathLossExponent));
                    mBleService.saveCalibrationResultToPrefs(mac, getBeaconDisplayName(mac), result);
                    Log.e(TAG, "[CALLBACK-TRACE] Step 4: BleService.saveCalibrationResultToPrefs completed");
                } else {
                    Log.e(TAG, "[ERROR] BleService is null on save - adding to pending queue");
                    Log.e(TAG, String.format(Locale.US, "[CAL-APPLY] Adding to pending queue: mac=%s, tx1m=%.5f, n=%.5f", 
                           mac, result.txPowerAt1m, result.pathLossExponent));
                    
                    // 보류 큐에 등록
                    pendingCalibrationSaves.offer(new PendingCalibrationSave(mac, getBeaconDisplayName(mac), result));
                    Log.e(TAG, String.format("[CAL-APPLY] Pending queue size: %d", pendingCalibrationSaves.size()));
                }
                
                // 5. UI 즉시 반영을 위한 어댑터 알림
                runOnUiThread(() -> {
                    if (mDevListAdapter != null) {
                        mDevListAdapter.notifyDataSetChanged();
                    }
                });
                
                Toast.makeText(DeviceScanActivity.this, 
                              String.format("보정 완료: %s\ntx1m=%.2f, n=%.2f, R²=%.2f", 
                                          getBeaconDisplayName(mac), 
                                          result.txPowerAt1m, result.pathLossExponent, result.rSquared), 
                              Toast.LENGTH_LONG).show();
            }
            
            @Override
            public CalibrationSession.CalibrationResult loadCalibrationResult(String mac) {
                Prefs.CalibrationParams params = mPrefs.loadCalibration(mac);
                if (params != null) {
                    // 저장된 품질 지표로 실제 품질 재계산 (표시 시 재계산 아님 - 저장된 값 기준)
                    CalibrationSession.QualityRating rating = determineQualityRating(
                        params.rSquared, params.rmse, params.maxResidual);
                    
                    return new CalibrationSession.CalibrationResult(
                        params.txPowerAt1m, params.pathLossExponent,
                        params.rSquared, params.rmse, params.maxResidual, 
                        rating // 저장된 지표 기반 올바른 품질 판정
                    );
                }
                return null;
            }
            
            /**
             * 저장된 품질 지표로 품질 등급 결정 (CalibrationSession과 동일한 로직)
             */
            private CalibrationSession.QualityRating determineQualityRating(double rSquared, double rmse, double maxResidual) {
                // CalibrationSession과 동일한 판정 기준 사용 (maxResidual은 저장용이지만 판정에는 미사용)
                // GOOD: R² ≥ 0.85 && RMSE ≤ 3.0
                if (rSquared >= 0.85 && rmse <= 3.0) {
                    return CalibrationSession.QualityRating.GOOD;
                }
                // BORDERLINE: R² ≥ 0.70 && RMSE ≤ 5.0
                else if (rSquared >= 0.70 && rmse <= 5.0) {
                    return CalibrationSession.QualityRating.BORDERLINE;
                }
                // BAD: 그 외
                else {
                    return CalibrationSession.QualityRating.BAD;
                }
            }
            
            @Override
            public void resetBeaconFiltering(String mac) {
                if (mServiceBound && mBleService != null) {
                    mBleService.resetBeaconFiltering(mac);
                }
            }
        });
    }
    
    // Phase 3: 폰(태블릿) 알람 구현
    
    /**
     * 폰 알람 시작 (20초 반복 재생, 중지까지 지속)
     */
    private void startPhoneAlarm() {
        // [캘리브레이션] 가드: 캘리브레이션 중에는 알람 비활성
        if (isCalibrating) {
            Log.d(TAG, "Phone alarm blocked - calibration in progress");
            return;
        }
        
        try {
            if (mPhoneAlarmPlayer == null) {
                // 알람 톤 선택 (기본 알람 → 알림 순으로 대체)
                Uri alarmTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (alarmTone == null) {
                    alarmTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                }
                if (alarmTone == null) {
                    alarmTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                }
                
                if (alarmTone != null) {
                    mPhoneAlarmPlayer = MediaPlayer.create(this, alarmTone);
                    if (mPhoneAlarmPlayer != null) {
                        mPhoneAlarmPlayer.setLooping(true); // 무한 반복
                    }
                }
            }
            
            if (mPhoneAlarmPlayer != null) {
                mPhoneAlarmPlayer.start();
                Log.d(TAG, "Phone alarm started (looping)");
                
                // 토스트 피드백 표시
                toastShow("🔊 태블릿 알람 시작");
                
                // 버튼 상태 업데이트
                if (mBtnPhoneAlarm != null) {
                    mBtnPhoneAlarm.setText("알람중");
                }
            } else {
                Log.w(TAG, "Failed to create phone alarm player");
                toastShow("알람 톤을 재생할 수 없습니다");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting phone alarm: " + e.getMessage());
        }
    }
    
    /**
     * 폰 알람 중지
     */
    private void stopPhoneAlarm() {
        try {
            if (mPhoneAlarmPlayer != null) {
                if (mPhoneAlarmPlayer.isPlaying()) {
                    mPhoneAlarmPlayer.stop();
                }
                mPhoneAlarmPlayer.release();
                mPhoneAlarmPlayer = null;
                Log.d(TAG, "Phone alarm stopped");
                
                // 토스트 피드백 표시
                toastShow("🔇 태블릿 알람 중지");
            }
            
            // 버튼 상태 복원
            if (mBtnPhoneAlarm != null) {
                mBtnPhoneAlarm.setText("폰 알람");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping phone alarm: " + e.getMessage());
        }
    }
    
    private boolean isLocationEnabledSafe() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } catch (Throwable t) {
            return false;
        }
    }
    
    /**
     * 버튼 상태 업데이트 (CLAUDE.md 스타일)
     */
    private void updateButtonState(String mac, String state) {
        if (mac == null || state == null) return;
        
        buttonStates.put(mac, state);
        
        // 어댑터에 데이터 변경 알림 (UI 갱신)
        runOnUiThread(() -> {
            if (mDevListAdapter != null) {
                mDevListAdapter.notifyDataSetChanged();
            }
        });
        
        Log.d(TAG, String.format("[BUTTON-STATE] %s -> %s", mac, state));
    }

    /**
     * 버튼 상태 조회 (어댑터에서 호출)
     */
    public String getButtonState(String mac) {
        return buttonStates.getOrDefault(mac, "알람"); // 기본값은 "알람"
    }

    /**
     * 토스트 메시지 표시 헬퍼 메서드
     */
    public void toastShow(String message) {
        // 토스트 디바운스: 동일 문구가 1초 내에 이미 표시되었으면 무시
        long now = System.currentTimeMillis();
        if (message != null && message.equals(lastToastText) && 
            (now - lastToastTime) < TOAST_DEBOUNCE_MS) {
            Log.v(TAG, String.format("[TOAST-DEBOUNCE] Skipped duplicate toast: '%s' (within %dms)", 
                message, (now - lastToastTime)));
            return;
        }
        
        lastToastText = message != null ? message : "";
        lastToastTime = now;
        
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        Log.d(TAG, String.format("[TOAST-SHOW] %s", message));
    }
    
    /**
     * 비콘 표시명 조회 헬퍼 메서드
     */
    private String getBeaconDisplayName(String mac) {
        if (mBleService != null) {
            List<BeaconState> beaconStates = mBleService.getFilteredBeaconStates();
            for (BeaconState state : beaconStates) {
                if (mac.equalsIgnoreCase(state.getMac())) {
                    String displayName = state.getDisplayName();
                    return displayName != null && !displayName.isEmpty() ? displayName : state.getMac();
                }
            }
        }
        return mac != null && mac.length() > 6 ? mac.substring(mac.length() - 6) : "Unknown";
    }
    
    /**
     * RSSI를 거리로 변환하는 헬퍼 메서드
     * @param rssiFiltered 필터링된 RSSI 값 (dBm)
     * @param txPowerAt1m 1미터 거리에서의 RSSI (dBm)
     * @param pathLossExponent 경로 손실 지수 (n)
     * @return 계산된 거리 (미터)
     */
    private double calculateDistance(double rssiFiltered, double txPowerAt1m, double pathLossExponent) {
        if (!Double.isFinite(rssiFiltered) || !Double.isFinite(txPowerAt1m) || 
            !Double.isFinite(pathLossExponent) || pathLossExponent <= 0.0) {
            return Double.NaN;
        }
        
        if (rssiFiltered >= txPowerAt1m) {
            return 0.1; // 최소 거리 0.1m
        }
        
        // distance = 10^((txPowerAt1m - rssiFiltered)/(10 * n))
        double exponent = (txPowerAt1m - rssiFiltered) / (10.0 * pathLossExponent);
        double distance = Math.pow(10, exponent);
        
        // 합리적 범위로 제한 (0.1m ~ 200m)
        return Math.max(0.1, Math.min(200.0, distance));
    }
    
    /**
     * 보류된 캘리브레이션 저장 요청들을 처리
     */
    private void processPendingCalibrationSaves() {
        if (pendingCalibrationSaves.isEmpty()) {
            Log.d(TAG, "[CAL-APPLY] No pending calibration saves to process");
            return;
        }
        
        Log.e(TAG, String.format("[CAL-APPLY] Processing %d pending calibration saves", pendingCalibrationSaves.size()));
        
        while (!pendingCalibrationSaves.isEmpty()) {
            PendingCalibrationSave pending = pendingCalibrationSaves.poll();
            if (pending != null && mBleService != null) {
                Log.e(TAG, String.format(Locale.US, "[CAL-APPLY] Processing pending save: mac=%s, tx1m=%.5f, n=%.5f", 
                       pending.mac, pending.result.txPowerAt1m, pending.result.pathLossExponent));
                
                mBleService.saveCalibrationResultToPrefs(pending.mac, pending.displayName, pending.result);
                
                Log.e(TAG, String.format("[CAL-APPLY] Completed pending save for MAC: %s", pending.mac));
            }
        }
        
        Log.e(TAG, "[CAL-APPLY] All pending calibration saves processed");
    }
}