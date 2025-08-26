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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
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
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketIBeacon;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor;
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

import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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


    private ListView mListView;
    private LeDeviceListAdapter mDevListAdapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private int mScanFailedContinueNum = 0;

    private final static int  MAX_ERROR_SCAN_NUMBER = 2;
    private HashMap<String, KBeacon> mBeaconsDictory;
    private KBeacon[] mBeaconsArray;
    private KBeaconsMgr mBeaconsMgr;


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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        if (mBeaconsMgr.isScanning()) {
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
        
        // 500ms UI 갱신 시스템 초기화
        mUiUpdateHandler = new Handler();
        mUiUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateUiFromDataStore();
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
        mListView.setOnItemClickListener(this);


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

        // Phase 3: 기능 컴포넌트 초기화
        mRingManager = new RingManager(mBeaconsMgr, mBeaconDataStore);
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
                    // Phase 2 Part 3: 저장된 캘리브레이션 값 복원
                    double txPowerAt1m = mPrefs.getTxPowerAt1m(mac, beaconName, Prefs.getDefaultTxPowerAt1m());
                    double pathLossExponent = mPrefs.getN(mac, beaconName, Prefs.getDefaultPathLossExponent());
                    
                    distanceEstimator = new DistanceEstimator(txPowerAt1m, pathLossExponent, 0.30);
                    mDistanceEstimators.put(mac, distanceEstimator);
                    
                    Log.d(TAG, "Restored calibration for " + beaconName + ": txPower=" + txPowerAt1m + ", n=" + pathLossExponent);
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
                    case KBAdvType.IBeacon: {
                        KBAdvPacketIBeacon advIBeacon = (KBAdvPacketIBeacon) advPacket;
                        Log.v(LOG_TAG, "iBeacon uuid:" + advIBeacon.getUuid());
                        Log.v(LOG_TAG, "iBeacon major:" + advIBeacon.getMajorID());
                        Log.v(LOG_TAG, "iBeacon minor:" + advIBeacon.getMinorID());
                        break;
                    }

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
        if(id == R.id.menu_scan){
            handleStartScan();
            invalidateOptionsMenu();
        }
        else if(id == R.id.menu_stop){
            mBeaconsMgr.stopScanning();
            invalidateOptionsMenu();
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
        
        // BeaconState에서 현재 이름 조회 (로컬 별칭 우선)
        BeaconState beaconState = mBeaconDataStore.get(mac);
        if (beaconState != null && beaconState.getName() != null) {
            currentName = beaconState.getName();
        }
        
        showDeviceNameChangeDialog(mac, currentName != null ? currentName : "Unknown");
    }
    
    /**
     * 비콘 이름 변경 다이얼로그
     * @param mac 비콘 MAC 주소
     * @param currentName 현재 이름
     */
    private void showDeviceNameChangeDialog(String mac, String currentName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("비콘 이름 변경");
        
        // EditText 설정
        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setText(currentName);
        editText.setSelection(currentName.length()); // 커서를 끝으로
        editText.setHint("새 이름 입력 (1-20자)");
        editText.setSingleLine(true);
        
        // 다이얼로그에 EditText 추가
        builder.setView(editText);
        
        // 확인 버튼
        builder.setPositiveButton("확인", (dialog, which) -> {
            String newName = editText.getText().toString().trim();
            
            // 유효성 검사
            if (newName.isEmpty()) {
                toastShow("이름을 입력해주세요");
                return;
            }
            
            if (newName.length() > 20) {
                toastShow("이름은 20자 이하로 입력해주세요");
                return;
            }
            
            // 제어문자 및 특수문자 검사 (기본적인 검증)
            if (newName.matches(".*[\\p{Cntrl}].*")) {
                toastShow("제어문자는 사용할 수 없습니다");
                return;
            }
            
            // 이름 변경 저장
            saveBeaconName(mac, newName);
            
            Log.d(TAG, "Beacon name changed: " + mac + " -> " + newName);
            toastShow("이름이 변경되었습니다: " + newName);
        });
        
        // 취소 버튼
        builder.setNegativeButton("취소", (dialog, which) -> dialog.dismiss());
        
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    /**
     * 비콘 이름을 BeaconState와 Prefs에 저장
     * @param mac 비콘 MAC 주소
     * @param newName 새 이름
     */
    private void saveBeaconName(String mac, String newName) {
        // BeaconState 업데이트
        BeaconState beaconState = mBeaconDataStore.get(mac);
        if (beaconState != null) {
            beaconState.setName(newName);
            mBeaconDataStore.upsert(beaconState);
        } else {
            // 새로운 BeaconState 생성 (일반적으로는 발생하지 않음)
            beaconState = new BeaconState(newName, mac);
            mBeaconDataStore.upsert(beaconState);
        }
        
        // Prefs에 MAC/이름 dual-key로 저장 (기존 저장 훅과 동일한 방식)
        if (mPrefs != null) {
            // 이름 키로 저장 (이전 이름도 동기화하기 위함)
            mPrefs.setDistanceThreshold(mac, newName, beaconState.getDistanceThresholdMeters());
            mPrefs.setBatteryPct(mac, newName, beaconState.getBatteryPercent());
            mPrefs.setTxPowerAt1m(mac, newName, beaconState.getTxPowerAt1m());
            mPrefs.setN(mac, newName, beaconState.getPathLossExponent());
            
            Log.d(TAG, "Saved beacon name to prefs: " + mac + " -> " + newName);
        }
        
        // UI는 500ms 주기 갱신에서 자동으로 반영됨 (별도 invalidate 불필요)
    }

    /**
     * 500ms 주기로 BeaconDataStore에서 데이터를 가져와 UI 갱신
     */
    private void updateUiFromDataStore() {
        try {
            List<BeaconState> beaconStates = mBeaconDataStore.getValidBeacons();
            mDevListAdapter.updateBeaconStates(beaconStates);
            mDevListAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            Log.d(TAG, "Error updating UI from data store: " + e.getMessage());
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

        mBeaconsMgr.stopScanning();
        invalidateOptionsMenu();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Phase 3: 기능 컴포넌트 정리
        if (mRingManager != null) {
            mRingManager.shutdown();
        }
        if (mBatteryScheduler != null) {
            mBatteryScheduler.shutdown();
        }
        stopPhoneAlarm();

        mBeaconsMgr.clearBeacons();
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

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
    
    @Override
    public void onRingStart(String mac) {
        if (mRingManager != null) {
            boolean started = mRingManager.start(mac);
            Log.d(TAG, "Ring start requested for MAC: " + mac + ", result: " + started);
        }
    }
    
    @Override
    public void onRingStop(String mac) {
        if (mRingManager != null) {
            boolean stopped = mRingManager.stop(mac);
            Log.d(TAG, "Ring stop requested for MAC: " + mac + ", result: " + stopped);
        }
    }
    
    @Override
    public void onDistanceSetting(String mac) {
        Log.d(TAG, "Distance setting requested for MAC: " + mac);
        
        // BeaconState에서 현재 거리 임계값 조회
        BeaconState beaconState = mBeaconDataStore.get(mac);
        double currentThreshold = (beaconState != null) ? 
            beaconState.getDistanceThresholdMeters() : Prefs.getDefaultDistanceThreshold();
        String beaconName = (beaconState != null && beaconState.getName() != null) ? 
            beaconState.getName() : "Unknown";
            
        showDistanceSettingDialog(mac, beaconName, currentThreshold);
    }
    
    /**
     * Phase 4C: 거리 임계값 설정 다이얼로그
     * @param mac 비콘 MAC 주소
     * @param beaconName 비콘 이름
     * @param currentThreshold 현재 임계값 (미터)
     */
    private void showDistanceSettingDialog(String mac, String beaconName, double currentThreshold) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("거리 임계값 설정 - " + beaconName);
        
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
        // TODO Phase 3 후속: 캘리브레이션 다이얼로그 구현  
        toastShow("캘리브레이션 기능은 추후 구현 예정");
    }
    
    // Phase 3: 폰(태블릿) 알람 구현
    
    /**
     * 폰 알람 시작 (20초 반복 재생, 중지까지 지속)
     */
    private void startPhoneAlarm() {
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