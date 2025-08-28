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
import com.kkmcn.sensordemo.ring.RingManager;
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
        KBeaconsMgr.KBeaconMgrDelegate, LeDeviceListAdapter.ListDataSource, LeDeviceListAdapter.OnRowActionListener {
	private final static String TAG = "Beacon.ScanAct";//DeviceScanActivity.class.getSimpleName();

    private static final String LOG_TAG = "ScanExample";

    private static final int PERMISSION_COARSE_LOCATION = 22;
    private static final int PERMISSION_FINE_LOCATION = 23;
    private static final int PERMISSION_SCAN = 24;
    private static final int PERMISSION_CONNECT = 25;
    private static final int REQ_PERMS = 1001; // 통합 권한 요청
    private static final int REQ_BT_ON = 1002; // 블루투스 활성화 요청


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
    private RingManager mRingManager;
    private BatteryScheduler mBatteryScheduler;
    private MediaPlayer mPhoneAlarmPlayer;
    
    // Phase 2 Part 3: 영속화 컴포넌트
    private Prefs mPrefs;
    
    // 500ms UI 갱신용
    private Handler mUiUpdateHandler;
    private Runnable mUiUpdateRunnable;
    private static final int UI_UPDATE_INTERVAL_MS = 500;
    
    // [터치디바운스] UI 갱신 제어 플래그
    private volatile boolean userTouchingList = false;
    private volatile long uiFreezeUntilMs = 0L;
    
    // [캘리브레이션] 가드 플래그 및 세션 관리
    private volatile boolean isCalibrating = false;
    private CalibrationSession activeCalibrationSession = null;
    
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
                    String mac = intent.getStringExtra("mac");
                    String state = intent.getStringExtra("state");
                    Log.d(TAG, String.format("Ring state changed: MAC=%s, state=%s", mac, state));
                    // TODO: 개별 버튼 상태 업데이트
                    break;
                    
                case BleService.ACTION_AUTO_ALARM_TRIGGERED:
                    String triggerMac = intent.getStringExtra("mac");
                    double distance = intent.getDoubleExtra("distance", 0.0);
                    double threshold = intent.getDoubleExtra("threshold", 0.0);
                    Log.w(TAG, String.format("Auto alarm triggered: MAC=%s, distance=%.1fm > threshold=%.1fm", 
                        triggerMac, distance, threshold));
                    
                    // 태블릿 알람 시작
                    runOnUiThread(() -> startPhoneAlarm());
                    break;
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
                    // BleService 연결 시 Service에서, 아니면 기존 DataStore에서 데이터 취득
                    if (mServiceBound && mBleService != null) {
                        updateUiFromService();
                    } else {
                        updateUiFromDataStore();
                    }
                } else {
                    Log.v("UI_UPDATE", String.format("Skipping UI update - touching=%s, frozen=%s", 
                        userTouchingList, (now < uiFreezeUntilMs)));
                }
                mUiUpdateHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
            }
        };

        mBeaconsMgr = KBeaconsMgr.sharedBeaconManager(this);
        if (mBeaconsMgr == null)
        {
            toastShow("make sure the phone has support ble funtion");
            finish();
            return;
        }
        mBeaconsMgr.delegate = this;
        mBeaconsMgr.setScanMode(KBeaconsMgr.SCAN_MODE_LOW_LATENCY);
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
        mRingManager = new RingManager(this, mBeaconsMgr, mBeaconDataStore);
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

    public void onBeaconDiscovered(KBeacon[] beacons)
    {
        // Phase 2: 데이터 처리 파이프라인 적용
        for (KBeacon beacon : beacons)
        {
            try {
                // (a) 이름 필터: 6자리 숫자로 시작하는 비콘만 처리
                String beaconName = beacon.getName();
                if (beaconName == null || !beaconName.matches("^\\d{6}_.+")) {
                    continue; // 필터 통과 실패시 스킵
                }
                
                String mac = beacon.getMac();
                int rawRssi = beacon.getRssi();
                
                // (b) BeaconDataStore에서 BeaconState 조회/생성
                BeaconState beaconState = mBeaconDataStore.get(mac);
                boolean isNewBeacon = (beaconState == null);
                if (isNewBeacon) {
                    beaconState = new BeaconState(beaconName, mac);
                    
                    // 별칭 로딩 (최초 탐지 시에만)
                    String savedAlias = mPrefs.getAlias(mac);
                    if (savedAlias != null) {
                        beaconState.setAliasName(savedAlias);
                        Log.d("ALIAS", "Loaded alias for " + mac + ": " + savedAlias);
                    }
                } else {
                    // 기존 비콘: 광고 이름만 업데이트, 별칭은 유지
                    beaconState.setName(beaconName);
                }
                
                // (c) RssiFilter로 rssiFiltered 산출
                RssiFilter rssiFilter = mRssiFilters.get(mac);
                if (rssiFilter == null) {
                    rssiFilter = new RssiFilter();
                    mRssiFilters.put(mac, rssiFilter);
                }
                double rssiFiltered = rssiFilter.addSample(rawRssi);
                
                // (d) DistanceEstimator로 distanceFiltered 산출
                DistanceEstimator distanceEstimator = mDistanceEstimators.get(mac);
                if (distanceEstimator == null) {
                    // Phase 2 Part 3: 저장된 캘리브레이션 값 복원 (캘리브레이션 우선)
                    double txPowerAt1m, pathLossExponent;
                    
                    // 캘리브레이션 결과 우선 로드 시도
                    Prefs.CalibrationParams calibParams = mPrefs.loadCalibration(mac);
                    if (calibParams != null) {
                        txPowerAt1m = calibParams.txPowerAt1m;
                        pathLossExponent = calibParams.pathLossExponent;
                        Log.d(TAG, String.format("Restored calibration result for %s: tx1m=%.2f, n=%.2f, R²=%.2f", 
                               beaconName, txPowerAt1m, pathLossExponent, calibParams.rSquared));
                    } else {
                        // 캘리브레이션 결과가 없으면 개별 설정값 또는 기본값 사용
                        txPowerAt1m = mPrefs.getTxPowerAt1m(mac, beaconName, Prefs.getDefaultTxPowerAt1m());
                        pathLossExponent = mPrefs.getN(mac, beaconName, Prefs.getDefaultPathLossExponent());
                        Log.d(TAG, "Using default/manual calibration for " + beaconName + ": txPower=" + txPowerAt1m + ", n=" + pathLossExponent);
                    }
                    
                    distanceEstimator = new DistanceEstimator(txPowerAt1m, pathLossExponent, 0.30);
                    mDistanceEstimators.put(mac, distanceEstimator);
                }
                double distanceFiltered = distanceEstimator.estimate(rssiFiltered);
                
                // (e) Phase 2 Part 3: 신규 비콘 시 거리 임계값과 배터리 복원
                if (isNewBeacon) {
                    // 거리 임계값 복원
                    double threshold = mPrefs.getDistanceThreshold(mac, beaconName, Prefs.getDefaultDistanceThreshold());
                    beaconState.setDistanceThreshold(threshold);
                    
                    // 배터리 값 복원 (이전에 저장된 값이 있다면)
                    Integer savedBattery = mPrefs.getBatteryPct(mac, beaconName, null);
                    if (savedBattery != null) {
                        beaconState.setBatteryPercent(savedBattery);
                        Log.d(TAG, "Restored settings for " + beaconName + ": threshold=" + threshold + ", battery=" + savedBattery + "%");
                    } else {
                        Log.d(TAG, "Restored threshold for " + beaconName + ": " + threshold + " m");
                    }
                }
                
                // (f) BeaconState 갱신 후 BeaconDataStore에 저장
                beaconState.setName(beaconName);
                beaconState.updateSignalState(rawRssi, rssiFiltered, distanceFiltered);
                
                // 현재 비콘에서 배터리 정보가 있다면 업데이트
                int currentBattery = beacon.getBatteryPercent();
                if (currentBattery > 0) {
                    beaconState.setBatteryPercent(currentBattery);
                }
                
                mBeaconDataStore.upsert(beaconState);
                
                // Phase 3: 신규 비콘 탐지 시 배터리 스케줄러에 알림
                if (isNewBeacon && mBatteryScheduler != null) {
                    mBatteryScheduler.onSeen(mac, beaconName);
                }
                
                // [캘리브레이션] 활성 세션이 있고 대상 MAC이면 샘플 전달
                if (activeCalibrationSession != null && 
                    mac.equalsIgnoreCase(activeCalibrationSession.getMac())) {
                    activeCalibrationSession.onRssiSample(rawRssi);
                    Log.v(TAG, String.format("Calibration sample: MAC=%s, RSSI=%d", mac, rawRssi));
                }
                
                // 기존 딕셔너리도 유지 (기존 로직 호환)
                mBeaconsDictory.put(mac, beacon);
                
            } catch (Exception e) {
                Log.d(TAG, "Error processing beacon: " + e.getMessage());
            }
        }
        
        // 기존 배열 업데이트 (기존 로직 호환)
        if (mBeaconsDictory.size() > 0) {
            mBeaconsArray = new KBeacon[mBeaconsDictory.size()];
            mBeaconsDictory.values().toArray(mBeaconsArray);
        }
        
        // UI 갱신은 500ms 주기로 별도 처리됨 (notifyDataSetChanged 제거)
    }

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

    public void onCentralBleStateChang(int nNewState)
    {
        Log.e(TAG, "centralBleStateChang：" + nNewState);
    }

    public void onScanFailed(int errorCode)
    {
        Log.e(TAG, "Start N scan failed：" + errorCode);
        if (mScanFailedContinueNum >= MAX_ERROR_SCAN_NUMBER){
            toastShow("scan encount error, error time:" + mScanFailedContinueNum);
        }
        mScanFailedContinueNum++;
    }

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
            Log.v("UI_UPDATE", String.format("updateUiFromService: beacons=%d", filteredBeacons.size()));
            
            mDevListAdapter.updateBeaconStates(filteredBeacons);
            mDevListAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            Log.d(TAG, "Error updating UI from service: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // 500ms UI 갱신 시작
        mUiUpdateHandler.post(mUiUpdateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        
        // 500ms UI 갱신 중지
        mUiUpdateHandler.removeCallbacks(mUiUpdateRunnable);
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
        
        // 브로드캐스트 리시버 해제
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceBroadcastReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister broadcast receiver", e);
        }
        
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
        if (mRingManager != null) {
            mRingManager.shutdown();
        }
        if (mBatteryScheduler != null) {
            mBatteryScheduler.shutdown();
        }
        stopPhoneAlarm();

        if (mBeaconsMgr != null) {
            mBeaconsMgr.clearBeacons();
        }
    }

    private void handlePeriodChk(){
        long currTick = System.currentTimeMillis();
    }

    private void handleStartScan(){
        if (!checkBluetoothPermitAllowed())
        {
            return;
        }

        int nStartScan = mBeaconsMgr.startScanning();
        if (nStartScan == 0)
        {
            Log.v(TAG, "start scan success");
        }
        else if (nStartScan == KBeaconsMgr.SCAN_ERROR_BLE_NOT_ENABLE)
        {
            toastShow("BLE function is not enable");
        }
        else if (nStartScan == KBeaconsMgr.SCAN_ERROR_UNKNOWN)
        {
            toastShow("Please make sure the app has BLE scan permission");
        }
    }

    private boolean checkBluetoothPermitAllowed() {
        boolean bHasPermission = true;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_FINE_LOCATION);
            bHasPermission = false;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSION_COARSE_LOCATION);
            bHasPermission = false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_SCAN},
                        PERMISSION_SCAN);
                bHasPermission = false;
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        PERMISSION_CONNECT);
                bHasPermission = false;
            }
        }

        return bHasPermission;
    }
    
    /**
     * 크래시 방지: Android 버전별 필수 권한 배열 반환
     * @return 필수 권한 배열
     */
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+
            return new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS // 알림 권한 추가
            };
        } else if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            return new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
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
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQ_PERMS);
            return;
        }
        
        // 2) 블루투스 ON 확인 (OFF면 먼저 요청)
        if (!isBtEnabled()) {
            Log.w(TAG, "Bluetooth OFF, requesting enable...");
            Intent btIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(btIntent, REQ_BT_ON);
            return;
        }
        
        // 3) 한 프레임 늦춰서 시작(권한 다이얼로그 복귀 타이밍 레이스 방지)
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Log.i(TAG, "Starting BleService with all safety checks passed");
                
                Intent serviceIntent = new Intent(this, BleService.class);
                ContextCompat.startForegroundService(this, serviceIntent);
                bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
                
                // 브로드캐스트 리시버 등록
                IntentFilter filter = new IntentFilter();
                filter.addAction(BleService.ACTION_BEACON_UPDATE);
                filter.addAction(BleService.ACTION_SCAN_STATE_CHANGED);
                filter.addAction(BleService.ACTION_RING_STATE_CHANGED);
                filter.addAction(BleService.ACTION_AUTO_ALARM_TRIGGERED);
                LocalBroadcastManager.getInstance(this).registerReceiver(mServiceBroadcastReceiver, filter);
                
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

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
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED){
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
        
        // [터치디바운스] 클릭 직후 250ms 프리즈로 리스너 재설정 레이스 추가 차단
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 250;
        
        // BleService 기반 Ring 시작
        if (mServiceBound && mBleService != null) {
            mBleService.startRingAlarm(mac);
            Log.i("RING", "BleService.startRingAlarm called for MAC: " + mac);
        } else {
            // 폴백: 기존 RingManager 사용
            if (mRingManager != null) {
                Log.d("RING", String.format("Fallback: RingManager.start for MAC=%s", mac));
                boolean started = mRingManager.start(mac, 2000);
                Log.i("RING", String.format("RingManager.start result: %s for MAC=%s", started, mac));
                if (!started) {
                    Log.w("RING", "RingManager.start failed - already running or busy?");
                }
            } else {
                Log.e("RING", "Both BleService and RingManager are unavailable!");
            }
        }
    }
    
    @Override
    public void onRingStop(String mac) {
        // [캘리브레이션] 가드: 캘리브레이션 중에는 수동 알람 비활성
        if (isCalibrating) {
            Log.d(TAG, "Ring stop blocked - calibration in progress");
            return;
        }
        
        Log.d("RING", String.format("UI onRingStop: MAC=%s", mac));
        
        // [터치디바운스] 클릭 직후 250ms 프리즈로 리스너 재설정 레이스 추가 차단
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 250;
        
        // BleService 기반 Ring 중지
        if (mServiceBound && mBleService != null) {
            mBleService.stopRingAlarm(mac);
            Log.i("RING", "BleService.stopRingAlarm called for MAC: " + mac);
        } else {
            // 폴백: 기존 RingManager 사용
            if (mRingManager != null) {
                boolean stopped = mRingManager.stop(mac);
                Log.i("RING_STOP", "RingManager.stop result: " + stopped + " for MAC: " + mac);
            } else {
                Log.e("RING", "Both BleService and RingManager are unavailable!");
            }
        }
    }
    
    @Override
    public void onDistanceSetting(String mac) {
        Log.d(TAG, "Distance setting requested for MAC: " + mac);
        
        // [터치디바운스] 다이얼로그 띄우는 동안 UI 갱신 금지
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 300;
        
        // [A] 별칭 기준 일관화: 거리설정 다이얼로그도 별칭 우선
        BeaconState beaconState = mBeaconDataStore.get(mac);
        double currentThreshold = (beaconState != null) ? 
            beaconState.getDistanceThresholdMeters() : Prefs.getDefaultDistanceThreshold();
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
            
            // 거리 임계값 저장
            saveDistanceThreshold(mac, beaconName, newThreshold);
            
            // BeaconState 즉시 갱신
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
        uiFreezeUntilMs = SystemClock.uptimeMillis() + 300;
        
        // 캘리브레이션 다이얼로그 표시
        CalibrationDialog.show(this, mac, new CalibrationDialog.CalibrationCallback() {
            @Override
            public void onCalibrationStarted() {
                isCalibrating = true;
                Log.i(TAG, "Calibration started - alarm guard activated");
            }
            
            @Override
            public void onCalibrationFinished(boolean saved) {
                isCalibrating = false;
                activeCalibrationSession = null;
                Log.i(TAG, "Calibration finished - alarm guard deactivated, saved=" + saved);
            }
            
            @Override
            public void onSampleNeeded(CalibrationSession session) {
                activeCalibrationSession = session;
                // 현재 스캔에서 해당 MAC의 RSSI를 세션에 전달하는 것은 
                // onBeaconDiscovered 콜백에서 처리됨
            }
            
            @Override
            public String getBeaconDisplayName(String mac) {
                BeaconState beaconState = mBeaconDataStore.get(mac);
                return (beaconState != null) ? beaconState.getDisplayName() : "Unknown";
            }
            
            @Override
            public void saveCalibrationResult(String mac, CalibrationSession.CalibrationResult result) {
                // 1. Prefs에 캘리브레이션 결과 저장
                mPrefs.saveCalibration(mac, result.txPowerAt1m, result.pathLossExponent, 
                                     result.rSquared, result.rmse, result.timestampMs);
                
                // 2. BeaconState 업데이트
                BeaconState beaconState = mBeaconDataStore.get(mac);
                if (beaconState != null) {
                    beaconState.setTxPowerAt1m(result.txPowerAt1m);
                    beaconState.setPathLossExponent(result.pathLossExponent);
                }
                
                // 3. DistanceEstimator에 새 캘리브레이션 적용
                DistanceEstimator estimator = mDistanceEstimators.get(mac);
                if (estimator != null) {
                    estimator.setCalibration(result.txPowerAt1m, result.pathLossExponent);
                    Log.i(TAG, String.format("Applied calibration to estimator: MAC=%s, tx1m=%.2f, n=%.2f", 
                           mac, result.txPowerAt1m, result.pathLossExponent));
                }
                
                // 4. UI 즉시 반영을 위한 어댑터 알림
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
                    return new CalibrationSession.CalibrationResult(
                        params.txPowerAt1m, params.pathLossExponent,
                        params.rSquared, params.rmse, 0.0, // maxResidual은 저장하지 않으므로 0으로 설정
                        CalibrationSession.QualityRating.GOOD // 저장된 결과는 GOOD으로 가정
                    );
                }
                return null;
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
            }
            
            // 버튼 상태 복원
            if (mBtnPhoneAlarm != null) {
                mBtnPhoneAlarm.setText("폰 알람");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping phone alarm: " + e.getMessage());
        }
    }
}