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
import com.kkmcn.sensordemo.model.BeaconState;
import com.kkmcn.sensordemo.utils.RssiFilter;
import com.kkmcn.sensordemo.utils.DistanceEstimator;

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
        KBeaconsMgr.KBeaconMgrDelegate, LeDeviceListAdapter.ListDataSource{
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

        // 하단 폰 알람 버튼만 유지
        mBtnPhoneAlarm = (Button) findViewById(R.id.btn_phone_alarm);
        mBtnPhoneAlarmStop = (Button) findViewById(R.id.btn_phone_alarm_stop);
        
        // TODO: Phase 2-3에서 폰 알람 로직 연결
        // mBtnPhoneAlarm.setOnClickListener() - 폰 알람 시작
        // mBtnPhoneAlarmStop.setOnClickListener() - 폰 알람 중지

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
                if (beaconState == null) {
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
                    // 기본값 사용: txPowerAt1m=-59, n=2.0
                    distanceEstimator = new DistanceEstimator();
                    // TODO: 캘리브레이션 값이 있으면 추후 적용
                    mDistanceEstimators.put(mac, distanceEstimator);
                }
                double distanceFiltered = distanceEstimator.estimate(rssiFiltered);
                
                // (e) BeaconState 갱신 후 BeaconDataStore에 저장
                beaconState.setName(beaconName);
                beaconState.updateSignalState(rawRssi, rssiFiltered, distanceFiltered);
                beaconState.setBatteryPercent(beacon.getBatteryPercent());
                
                mBeaconDataStore.upsert(beaconState);
                
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
    
    // 이름 변경 다이얼로그 표시 메서드
    private void showDeviceNameChangeDialog(KBeacon beacon) {
        // TODO: Phase 2에서 구현
        // - EditText가 포함된 AlertDialog 생성
        // - 최대 18자, 한글 입력 지원
        // - 확인 시 beacon.setName() 호출
        toastShow("이름 변경 - TODO");
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
}