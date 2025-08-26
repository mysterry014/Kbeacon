package com.kkmcn.sensordemo;

import android.content.Context;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAccSensorValue;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyTLM;
// iBeacon import removed - using KBeacon protocol only
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvType;
import com.kkmcn.kbeaconlib2.KBeacon;
import com.kkmcn.sensordemo.model.BeaconState;

import java.util.ArrayList;
import java.util.List;


public class LeDeviceListAdapter extends BaseAdapter {

	// Adapter for holding devices found through scanning.
	public interface ListDataSource {
		KBeacon getBeaconDevice(int nIndex);

		int getCount();
	}
	
	// Phase 3: 리스트 항목 버튼 액션 콜백 인터페이스
	public interface OnRowActionListener {
		void onRingStart(String mac);
		void onRingStop(String mac);
		void onDistanceSetting(String mac);
		void onCalibration(String mac);
	}

	private ListDataSource mDataSource;
	private Context mContext;
	
	// Phase 2: BeaconState 기반 데이터 관리
	private List<BeaconState> mBeaconStates;
	
	// Phase 3: 콜백 리스너
	private OnRowActionListener mOnRowActionListener;

	public LeDeviceListAdapter(ListDataSource c, Context ctx) {
		super();
		mDataSource = c;
		mContext = ctx;
		mBeaconStates = new ArrayList<>();
	}
	
	/**
	 * Phase 2: BeaconState 리스트 업데이트
	 * @param beaconStates 새로운 BeaconState 리스트
	 */
	public void updateBeaconStates(List<BeaconState> beaconStates) {
		this.mBeaconStates = beaconStates != null ? new ArrayList<>(beaconStates) : new ArrayList<>();
	}
	
	/**
	 * Phase 3: 콜백 리스너 설정
	 * @param listener OnRowActionListener 인스턴스
	 */
	public void setOnRowActionListener(OnRowActionListener listener) {
		this.mOnRowActionListener = listener;
	}

	@Override
	public int getCount() {
		// Phase 2: BeaconState 기반 카운트 사용
		return mBeaconStates.size();
	}

	@Override
	public Object getItem(int i) {
		// Phase 2: BeaconState 반환
		return i >= 0 && i < mBeaconStates.size() ? mBeaconStates.get(i) : null;
	}

	@Override
	public long getItemId(int i) {
		return i;
	}


	@Override
	public View getView(int i, View view, ViewGroup viewGroup)
	{
		ViewHolder viewHolder;
		// General ListView optimization code.
		if (view == null)
		{
			view = LayoutInflater.from(mContext).inflate(R.layout.listitem_device, null);
			viewHolder = new ViewHolder();
			
			// Phase 1 새로운 UI 요소들
			viewHolder.tvBeaconName = view.findViewById(R.id.tv_beacon_name);
			viewHolder.tvRssi = view.findViewById(R.id.tv_rssi);
			viewHolder.tvBattery = view.findViewById(R.id.tv_battery);
			viewHolder.tvDistance = view.findViewById(R.id.tv_distance);
			viewHolder.btnRingAlarm = view.findViewById(R.id.btn_ring_alarm);
			viewHolder.btnRingAlarmStop = view.findViewById(R.id.btn_ring_alarm_stop);
			viewHolder.btnDistanceSetting = view.findViewById(R.id.btn_distance_setting);
			viewHolder.btnCalibration = view.findViewById(R.id.btn_calibration);
			
			// 기존 UI 요소들 (숨김 처리된 레이아웃용)
			viewHolder.deviceName = view
					.findViewById(R.id.beacon_name);

			viewHolder.deviceMacAddr =  view
					.findViewById(R.id.beacon_mac_address);

			viewHolder.rssiState = view
					.findViewById(R.id.beacon_rssi);

			viewHolder.deviceBatteryPercent = view
					.findViewById(R.id.beacon_battery_percent);

			//tlm
			viewHolder.llEddyTLM = view
					.findViewById(R.id.ll_eddy_tlm);
			viewHolder.deviceEddyTLM = view
					.findViewById(R.id.tv_tlm_beacon);

			// iBeacon UI elements removed - using KBeacon protocol only


			//sensor 1
			viewHolder.llSensorItem1= view
					.findViewById(R.id.ll_sensor_item1);
			viewHolder.txtDeviceItem1 = view
					.findViewById(R.id.tv_sensor_item1);

			// sensor 2
			viewHolder.llSensorItem2= view
					.findViewById(R.id.ll_sensor_item2);
			viewHolder.txtDeviceItem2 = view
					.findViewById(R.id.tv_sensor_item2);

			view.setTag(viewHolder);
		}
		else
		{
			viewHolder = (ViewHolder) view.getTag();
		}

		// Phase 2: BeaconState 기반 데이터 바인딩
		if (i >= mBeaconStates.size()) {
			return view; // 인덱스 오버플로우 방지
		}
		
		BeaconState beaconState = mBeaconStates.get(i);
		if (beaconState == null) {
			return view;
		}

		// Phase 2 새로운 UI 업데이트 (BeaconState 기반)
		// 1. 이름 표시
		String displayName = beaconState.getName();
		if (displayName == null || displayName.isEmpty()) {
			// 이름이 없으면 MAC 일부로 대체
			String mac = beaconState.getMac();
			displayName = mac != null && mac.length() > 6 ? 
				mac.substring(mac.length() - 6) : "Unknown";
		}
		viewHolder.tvBeaconName.setText(displayName);
		viewHolder.tvBeaconName.setEllipsize(android.text.TextUtils.TruncateAt.END);
		viewHolder.tvBeaconName.setMaxLines(1);
		
		// 2. RSSI 표시 (필터링된 값)
		double rssiFiltered = beaconState.getRssiFiltered();
		String rssiText = rssiFiltered != 0.0 ? 
			String.format("%.0f dBm", rssiFiltered) : "–";
		viewHolder.tvRssi.setText(rssiText);
		
		// 3. 배터리 표시
		int batteryPercent = beaconState.getBatteryPercent();
		String batteryText = batteryPercent > 0 ? 
			String.format("%d%%", batteryPercent) : "--%";
		viewHolder.tvBattery.setText(batteryText);
		
		// 4. 거리 표시 (필터링된 값, 소수 1자리)
		double distanceFiltered = beaconState.getDistanceFiltered();
		String distanceText = distanceFiltered > 0.0 ? 
			String.format("%.1f m", distanceFiltered) : "–";
		viewHolder.tvDistance.setText(distanceText);
		
		// Phase 3: 버튼 클릭 리스너 연결
		String mac = beaconState.getMac();
		
		// 부저 알람 시작 버튼
		viewHolder.btnRingAlarm.setOnClickListener(v -> {
			if (mOnRowActionListener != null) {
				mOnRowActionListener.onRingStart(mac);
			}
		});
		
		// 부저 알람 중지 버튼
		viewHolder.btnRingAlarmStop.setOnClickListener(v -> {
			if (mOnRowActionListener != null) {
				mOnRowActionListener.onRingStop(mac);
			}
		});
		
		// 거리 설정 버튼 (TODO: Phase 3 후속 단계에서 다이얼로그 구현)
		viewHolder.btnDistanceSetting.setOnClickListener(v -> {
			if (mOnRowActionListener != null) {
				mOnRowActionListener.onDistanceSetting(mac);
			}
		});
		
		// 캘리브레이션 버튼 (TODO: Phase 3 후속 단계에서 구현)
		viewHolder.btnCalibration.setOnClickListener(v -> {
			if (mOnRowActionListener != null) {
				mOnRowActionListener.onCalibration(mac);
			}
		});

		// 기존 UI 업데이트 (호환성 유지, 숨김 처리된 레이아웃용)
		if (viewHolder.deviceName != null) {
			viewHolder.deviceName.setText(displayName);
		}
		if (viewHolder.deviceMacAddr != null && beaconState.getMac() != null) {
			String strMacAddress = mContext.getString(R.string.BEACON_MAC_ADDRESS) + beaconState.getMac();
			viewHolder.deviceMacAddr.setText(strMacAddress);
		}
		if (viewHolder.rssiState != null) {
			String strRssiValue = mContext.getString(R.string.BEACON_RSSI_VALUE) + beaconState.getLastRssi();
			viewHolder.rssiState.setText(strRssiValue);
		}
		if (viewHolder.deviceBatteryPercent != null) {
			String strBattPercent = mContext.getString(R.string.BEACON_BATTERY) + batteryText;
			viewHolder.deviceBatteryPercent.setText(strBattPercent);
		}

		// Phase 2: 기존 iBeacon/EddyTLM/Sensor 처리 코드 제거
		// - 6자리 필터로 인해 KSensor 프로토콜 비콘만 처리
		// - BeaconState 기반으로 데이터 표시 완료
		// - 기존 숨김 처리된 레이아웃 요소들도 비표시 처리
		if (viewHolder.llEddyTLM != null) {
			viewHolder.llEddyTLM.setVisibility(View.GONE);
		}
		// iBeacon UI hide code removed - fields no longer exist
		if (viewHolder.llSensorItem1 != null) {
			viewHolder.llSensorItem1.setVisibility(View.GONE);
		}
		if (viewHolder.llSensorItem2 != null) {
			viewHolder.llSensorItem2.setVisibility(View.GONE);
		}

		return view;
	}

	static class ViewHolder {
		// Phase 1 새로운 UI 요소들
		TextView tvBeaconName;
		TextView tvRssi;
		TextView tvBattery;
		TextView tvDistance;
		Button btnRingAlarm;
		Button btnRingAlarmStop;
		Button btnDistanceSetting;
		Button btnCalibration;
		
		// 기존 UI 요소들 (숨김 처리된 레이아웃용)
		TextView deviceName;      //名称

		TextView rssiState;     //状态
		TextView deviceBatteryPercent;
		TextView deviceMacAddr;

		// iBeacon fields removed - using KBeacon protocol only
		LinearLayout llEddyTLM;

		TextView deviceEddyTLM;

		LinearLayout llSensorItem1;
		LinearLayout llSensorItem2;
		TextView txtDeviceItem1;
		TextView txtDeviceItem2;
	}
}
