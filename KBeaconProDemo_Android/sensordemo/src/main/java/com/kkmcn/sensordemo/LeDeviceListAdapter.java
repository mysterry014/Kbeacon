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
import androidx.annotation.NonNull;

import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAccSensorValue;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketEddyTLM;
// iBeacon import removed - using KBeacon protocol only
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvPacketSensor;
import com.kkmcn.kbeaconlib2.KBAdvPackage.KBAdvType;
import com.kkmcn.kbeaconlib2.KBeacon;
import com.kkmcn.sensordemo.model.BeaconState;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class LeDeviceListAdapter extends BaseAdapter {

	// Adapter for holding devices found through scanning
	public interface ListDataSource {
		KBeacon getBeaconDevice(int nIndex);

		int getCount();
	}
	
	// Phase 3: 리스트 항목 버튼 액션 콜백 인터페이스
	public interface OnRowActionListener {
		void onNameEdit(String mac, String currentName);  // 이름 클릭 진입점
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
	
	// 클릭 디바운싱을 위한 마지막 클릭 시간 추적 (MAC + 버튼타입별)
	private final Map<String, Long> mLastClickTimes = new HashMap<>();
	private static final long CLICK_DEBOUNCE_MS = 200; // [패치 C] 500ms → 200ms로 단축

	public LeDeviceListAdapter(ListDataSource c, Context ctx) {
		super();
		// [패치 C] Stable IDs 활성화 - hasStableIds() 메서드 오버라이드로 처리
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
	
	/**
	 * 클릭 디바운싱 체크: MAC + 버튼타입별로 최소 간격을 보장
	 * @param mac 비콘 MAC 주소
	 * @param buttonType 버튼 타입 ("ring", "stop", "distance", "calibration", "name")
	 * @return true if click is allowed, false if debounced
	 */
	private boolean isClickAllowed(String mac, String buttonType) {
		if (mac == null || buttonType == null) return false;
		
		String clickKey = mac + "_" + buttonType;
		long currentTime = System.currentTimeMillis();
		Long lastClickTime = mLastClickTimes.get(clickKey);
		
		if (lastClickTime == null || (currentTime - lastClickTime) >= CLICK_DEBOUNCE_MS) {
			mLastClickTimes.put(clickKey, currentTime);
			return true;
		}
		
		Log.d("LeDeviceListAdapter", String.format("Click debounced: %s (last: %dms ago)", 
			clickKey, currentTime - lastClickTime));
		return false;
	}
	
	// [수정1] BeaconState에서 별칭 우선 반환 (KBeacon 참조 제거)
	private @NonNull String getDisplayNameByMac(@NonNull String mac) {
		if (mBeaconStates != null) {
			for (BeaconState s : mBeaconStates) {
				if (mac.equalsIgnoreCase(s.getMac())) {
					String dn = s.getDisplayName(); // alias 우선, 없으면 광고명
					return dn != null ? dn : s.getMac();
				}
			}
		}
		return mac; // fallback
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
		// [패치 C] Stable ID 구현 - MAC 기반 안정적인 ID 제공
		if (i >= 0 && i < mBeaconStates.size()) {
			BeaconState state = mBeaconStates.get(i);
			if (state != null && state.getMac() != null) {
				String mac = state.getMac();
				// MAC 주소를 해시코드로 변환 (안정적이고 간단한 방법)
				return mac.hashCode();
			}
		}
		return i;
	}
	
	@Override
	public boolean hasStableIds() {
		// [패치 C] 안정적 ID 활성화: ListView 재활용 최적화
		return true;
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
		// 1. 이름 표시 (별칭 우선, 없으면 광고 이름)
		String displayName = beaconState.getDisplayName();
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
		String batteryText = batteryPercent >= 0 ? 
			String.format("%d%%", batteryPercent) : "--%";
		viewHolder.tvBattery.setText(batteryText);
		
		// 4. 거리 표시 (필터링된 값, 소수 1자리)
		double distanceFiltered = beaconState.getDistanceFiltered();
		String distanceText = distanceFiltered > 0.0 ? 
			String.format("%.1f m", distanceFiltered) : "–";
		viewHolder.tvDistance.setText(distanceText);
		
		// [B] 클릭 대상 혼동 제거: 매 바인딩마다 리스너 완전 재설정
		final String captureMac = beaconState.getMac();  // 클로저 MAC 캡처 (안정적)
		
		// [디버깅] 뷰 바인딩 정보 로깅
		Log.d("LeDeviceListAdapter", 
			String.format("getView: position=%d, MAC=%s, name=%s, viewType=%s", 
				i, captureMac, beaconState.getDisplayName(), 
				(view == null ? "NEW" : "RECYCLED")));
		
		// [터치디바운스] 리스너는 MAC이 바뀔 때만 재설정 (리스너-재설정/클릭 레이스 감소)
		if (viewHolder.boundMac == null || !viewHolder.boundMac.equalsIgnoreCase(captureMac)) {
			Log.d("LeDeviceListAdapter", String.format("MAC changed, resetting listeners: position=%d, oldMAC=%s, newMAC=%s", 
				i, viewHolder.boundMac, captureMac));
		
			// [수정1] 이름 TextView 클릭 리스너 - MAC만 전달 (Activity에서 별칭 재조회)
			viewHolder.tvBeaconName.setOnClickListener(v -> {
				if (!isClickAllowed(captureMac, "name")) {
					Log.d("LeDeviceListAdapter", "Name click debounced for MAC: " + captureMac);
					return;
				}
				
				if (mOnRowActionListener != null && captureMac != null) {
					// MAC만 넘김; 이름은 Activity에서 재조회
					mOnRowActionListener.onNameEdit(captureMac, null);
				}
			});
		
			// 부저 알람 시작 버튼
			Log.d("LeDeviceListAdapter", String.format("Setting RingAlarm listener for position=%d, MAC=%s", i, captureMac));
			viewHolder.btnRingAlarm.setOnClickListener(v -> {
				if (!isClickAllowed(captureMac, "ring")) {
					Log.d("LeDeviceListAdapter", "Ring alarm click debounced for MAC: " + captureMac);
					// 시각적 피드백 추가 - 사용자가 왜 반응 없는지 알 수 있도록
					android.widget.Toast.makeText(v.getContext(), "잠시만 기다려주세요...", android.widget.Toast.LENGTH_SHORT).show();
					return;
				}
				
				// [패치 C] 촉각 피드백만 제공, 버튼 비활성화 제거로 반응성 향상
				v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
				
				Log.d("RingAlarm", String.format("CLICK: position=%d, MAC=%s, name=%s", 
					i, captureMac, beaconState.getDisplayName()));
				
				if (mOnRowActionListener != null && captureMac != null) {
					Log.d("RingAlarm", "Calling onRingStart for MAC: " + captureMac);
					mOnRowActionListener.onRingStart(captureMac);
				} else {
					Log.w("RingAlarm", "Listener or MAC is null: listener=" + mOnRowActionListener + ", mac=" + captureMac);
				}
			});
			
			// 부저 알람 중지 버튼
			Log.d("LeDeviceListAdapter", String.format("Setting RingStop listener for position=%d, MAC=%s", i, captureMac));
			viewHolder.btnRingAlarmStop.setOnClickListener(v -> {
				if (!isClickAllowed(captureMac, "stop")) {
					Log.d("LeDeviceListAdapter", "Ring stop click debounced for MAC: " + captureMac);
					// 시각적 피드백 추가
					android.widget.Toast.makeText(v.getContext(), "잠시만 기다려주세요...", android.widget.Toast.LENGTH_SHORT).show();
					return;
				}
				
				// [패치 C] 촉각 피드백만 제공, 버튼 비활성화 제거로 반응성 향상
				v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
				
				Log.d("RingStop", String.format("CLICK: position=%d, MAC=%s, name=%s", 
					i, captureMac, beaconState.getDisplayName()));
				
				if (mOnRowActionListener != null && captureMac != null) {
					Log.d("RingStop", "Calling onRingStop for MAC: " + captureMac);
					mOnRowActionListener.onRingStop(captureMac);
				} else {
					Log.w("RingStop", "Listener or MAC is null: listener=" + mOnRowActionListener + ", mac=" + captureMac);
				}
			});
			
			// 거리 설정 버튼
			viewHolder.btnDistanceSetting.setOnClickListener(v -> {
				if (!isClickAllowed(captureMac, "distance")) {
					Log.d("LeDeviceListAdapter", "Distance setting click debounced for MAC: " + captureMac);
					// 시각적 피드백 추가
					android.widget.Toast.makeText(v.getContext(), "잠시만 기다려주세요...", android.widget.Toast.LENGTH_SHORT).show();
					return;
				}
				
				// [패치 C] 촉각 피드백만 제공, 버튼 비활성화 제거로 반응성 향상
				v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
				
				if (mOnRowActionListener != null && captureMac != null) {
					mOnRowActionListener.onDistanceSetting(captureMac);
				}
			});
			
			// 캘리브레이션 버튼
			viewHolder.btnCalibration.setOnClickListener(v -> {
				if (!isClickAllowed(captureMac, "calibration")) {
					Log.d("LeDeviceListAdapter", "Calibration click debounced for MAC: " + captureMac);
					// 시각적 피드백 추가
					android.widget.Toast.makeText(v.getContext(), "잠시만 기다려주세요...", android.widget.Toast.LENGTH_SHORT).show();
					return;
				}
				
				// [패치 C] 촉각 피드백만 제공, 버튼 비활성화 제거로 반응성 향상
				v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
				
				if (mOnRowActionListener != null && captureMac != null) {
					mOnRowActionListener.onCalibration(captureMac);
				}
			});
			
			// boundMac 업데이트
			viewHolder.boundMac = captureMac;
		} else {
			// MAC이 같을 때는 리스너 재설정 스킵
			Log.v("LeDeviceListAdapter", String.format("MAC unchanged, skipping listener reset: position=%d, MAC=%s", i, captureMac));
		}

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
		
		// [터치디바운스] 마지막으로 리스너를 바인딩했던 MAC
		String boundMac;
		
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
