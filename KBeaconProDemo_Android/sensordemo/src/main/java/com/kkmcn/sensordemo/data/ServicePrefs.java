package com.kkmcn.sensordemo.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * BleService 전용 SharedPreferences 관리 클래스
 * 
 * 저장 데이터:
 * - 캘리브레이션 결과 (MAC별 txPowerAt1m, n, R², RMSE)
 * - 거리 설정값 (MAC별)
 * - 배터리 정보 및 마지막 업데이트 시간
 * - 장치 별칭 (displayName)
 * - MAC 수집 게이트 상태
 */
public class ServicePrefs {
    
    private static final String TAG = "ServicePrefs";
    private static final String PREFS_NAME = "ble_service_prefs";
    
    // Keys for different data types
    private static final String KEY_CALIBRATION_DATA = "calibration_data";
    private static final String KEY_DISTANCE_THRESHOLDS = "distance_thresholds";
    private static final String KEY_BATTERY_DATA = "battery_data";
    private static final String KEY_DEVICE_ALIASES = "device_aliases";
    private static final String KEY_MAC_GATE_REGISTRY = "mac_gate_registry";
    
    private final SharedPreferences prefs;
    
    public ServicePrefs(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    // ========== 캘리브레이션 데이터 관리 ==========
    
    /**
     * 캘리브레이션 결과 저장
     * @param mac MAC 주소
     * @param txPowerAt1m 1m 기준 RSSI
     * @param pathLossExponent 경로손실지수 n
     * @param rSquared 결정계수 R²
     * @param rmse 평균제곱근오차 RMSE
     */
    public void saveCalibrationResult(String mac, double txPowerAt1m, double pathLossExponent, 
                                    double rSquared, double rmse) {
        if (mac == null) return;
        
        try {
            JSONObject calibrationData = getCalibrationDataJson();
            
            JSONObject deviceData = new JSONObject();
            deviceData.put("txPowerAt1m", txPowerAt1m);
            deviceData.put("pathLossExponent", pathLossExponent);
            deviceData.put("rSquared", rSquared);
            deviceData.put("rmse", rmse);
            deviceData.put("timestamp", System.currentTimeMillis());
            
            calibrationData.put(mac, deviceData);
            
            prefs.edit()
                .putString(KEY_CALIBRATION_DATA, calibrationData.toString())
                .apply();
                
            Log.d(TAG, String.format("Calibration saved: MAC=%s, txPower=%.1f, n=%.2f, R²=%.3f, RMSE=%.1f", 
                mac, txPowerAt1m, pathLossExponent, rSquared, rmse));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save calibration data", e);
        }
    }
    
    /**
     * 캘리브레이션 결과 로드
     */
    public CalibrationResult getCalibrationResult(String mac) {
        if (mac == null) return null;
        
        try {
            JSONObject calibrationData = getCalibrationDataJson();
            if (!calibrationData.has(mac)) {
                return null;
            }
            
            JSONObject deviceData = calibrationData.getJSONObject(mac);
            return new CalibrationResult(
                deviceData.getDouble("txPowerAt1m"),
                deviceData.getDouble("pathLossExponent"),
                deviceData.getDouble("rSquared"),
                deviceData.getDouble("rmse"),
                deviceData.getLong("timestamp")
            );
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load calibration data for MAC: " + mac, e);
            return null;
        }
    }
    
    private JSONObject getCalibrationDataJson() {
        String json = prefs.getString(KEY_CALIBRATION_DATA, "{}");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "Invalid calibration JSON, resetting", e);
            return new JSONObject();
        }
    }
    
    // ========== 거리 설정값 관리 ==========
    
    /**
     * 거리 설정값 저장
     */
    public void saveDistanceThreshold(String mac, double threshold) {
        if (mac == null) return;
        
        try {
            JSONObject thresholds = getDistanceThresholdsJson();
            thresholds.put(mac, threshold);
            
            prefs.edit()
                .putString(KEY_DISTANCE_THRESHOLDS, thresholds.toString())
                .apply();
                
            Log.d(TAG, String.format("Distance threshold saved: MAC=%s, threshold=%.1fm", mac, threshold));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save distance threshold", e);
        }
    }
    
    /**
     * 거리 설정값 로드 (기본값: 50.0m)
     */
    public double getDistanceThreshold(String mac) {
        if (mac == null) return 50.0;
        
        try {
            JSONObject thresholds = getDistanceThresholdsJson();
            return thresholds.optDouble(mac, 50.0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load distance threshold for MAC: " + mac, e);
            return 50.0;
        }
    }
    
    private JSONObject getDistanceThresholdsJson() {
        String json = prefs.getString(KEY_DISTANCE_THRESHOLDS, "{}");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "Invalid distance thresholds JSON, resetting", e);
            return new JSONObject();
        }
    }
    
    // ========== 배터리 데이터 관리 ==========
    
    /**
     * 배터리 정보 저장
     */
    public void saveBatteryInfo(String mac, int batteryPercent, float voltage, long updateTime) {
        if (mac == null) return;
        
        try {
            JSONObject batteryData = getBatteryDataJson();
            
            JSONObject deviceBattery = new JSONObject();
            deviceBattery.put("percent", batteryPercent);
            deviceBattery.put("voltage", voltage);
            deviceBattery.put("updateTime", updateTime);
            
            batteryData.put(mac, deviceBattery);
            
            prefs.edit()
                .putString(KEY_BATTERY_DATA, batteryData.toString())
                .apply();
                
            Log.v(TAG, String.format("Battery saved: MAC=%s, percent=%d%%, voltage=%.2fV", 
                mac, batteryPercent, voltage));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save battery info", e);
        }
    }
    
    /**
     * 배터리 정보 로드
     */
    public BatteryInfo getBatteryInfo(String mac) {
        if (mac == null) return null;
        
        try {
            JSONObject batteryData = getBatteryDataJson();
            if (!batteryData.has(mac)) {
                return null;
            }
            
            JSONObject deviceBattery = batteryData.getJSONObject(mac);
            return new BatteryInfo(
                deviceBattery.getInt("percent"),
                (float) deviceBattery.getDouble("voltage"),
                deviceBattery.getLong("updateTime")
            );
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load battery info for MAC: " + mac, e);
            return null;
        }
    }
    
    private JSONObject getBatteryDataJson() {
        String json = prefs.getString(KEY_BATTERY_DATA, "{}");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "Invalid battery JSON, resetting", e);
            return new JSONObject();
        }
    }
    
    // ========== 장치 별칭 관리 ==========
    
    /**
     * 장치 별칭 저장
     */
    public void saveDeviceAlias(String mac, String alias) {
        if (mac == null) return;
        
        try {
            JSONObject aliases = getDeviceAliasesJson();
            if (alias != null && !alias.trim().isEmpty()) {
                aliases.put(mac, alias.trim());
            } else {
                aliases.remove(mac); // null이나 빈 문자열은 제거
            }
            
            prefs.edit()
                .putString(KEY_DEVICE_ALIASES, aliases.toString())
                .apply();
                
            Log.d(TAG, String.format("Device alias saved: MAC=%s, alias=%s", mac, alias));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save device alias", e);
        }
    }
    
    /**
     * 장치 별칭 로드
     */
    public String getDeviceAlias(String mac) {
        if (mac == null) return null;
        
        try {
            JSONObject aliases = getDeviceAliasesJson();
            return aliases.optString(mac, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load device alias for MAC: " + mac, e);
            return null;
        }
    }
    
    private JSONObject getDeviceAliasesJson() {
        String json = prefs.getString(KEY_DEVICE_ALIASES, "{}");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "Invalid device aliases JSON, resetting", e);
            return new JSONObject();
        }
    }
    
    // ========== MAC 수집 게이트 레지스트리 관리 ==========
    
    /**
     * MAC 수집 게이트에 등록
     */
    public void registerMacForCollection(String mac, String displayName) {
        if (mac == null) return;
        
        try {
            JSONObject registry = getMacGateRegistryJson();
            
            JSONObject entry = new JSONObject();
            entry.put("displayName", displayName != null ? displayName : "");
            entry.put("registeredTime", System.currentTimeMillis());
            
            registry.put(mac, entry);
            
            prefs.edit()
                .putString(KEY_MAC_GATE_REGISTRY, registry.toString())
                .apply();
                
            Log.d(TAG, String.format("MAC registered for collection: %s -> %s", mac, displayName));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to register MAC for collection", e);
        }
    }
    
    /**
     * MAC 수집 게이트에서 해제
     */
    public void unregisterMacForCollection(String mac) {
        if (mac == null) return;
        
        JSONObject registry = getMacGateRegistryJson();
        registry.remove(mac);
        
        prefs.edit()
            .putString(KEY_MAC_GATE_REGISTRY, registry.toString())
            .apply();
            
        Log.d(TAG, "MAC unregistered from collection: " + mac);
    }
    
    /**
     * 등록된 MAC 목록 반환
     */
    public Map<String, String> getRegisteredMacs() {
        Map<String, String> result = new HashMap<>();
        
        try {
            JSONObject registry = getMacGateRegistryJson();
            JSONArray names = registry.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String mac = names.getString(i);
                    JSONObject entry = registry.getJSONObject(mac);
                    String displayName = entry.getString("displayName");
                    result.put(mac, displayName);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load registered MACs", e);
        }
        
        return result;
    }
    
    private JSONObject getMacGateRegistryJson() {
        String json = prefs.getString(KEY_MAC_GATE_REGISTRY, "{}");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            Log.e(TAG, "Invalid MAC gate registry JSON, resetting", e);
            return new JSONObject();
        }
    }
    
    // ========== 데이터 클래스들 ==========
    
    public static class CalibrationResult {
        public final double txPowerAt1m;
        public final double pathLossExponent;
        public final double rSquared;
        public final double rmse;
        public final long timestamp;
        
        public CalibrationResult(double txPowerAt1m, double pathLossExponent, 
                               double rSquared, double rmse, long timestamp) {
            this.txPowerAt1m = txPowerAt1m;
            this.pathLossExponent = pathLossExponent;
            this.rSquared = rSquared;
            this.rmse = rmse;
            this.timestamp = timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("CalibrationResult{txPower=%.1f, n=%.2f, R²=%.3f, RMSE=%.1f}", 
                txPowerAt1m, pathLossExponent, rSquared, rmse);
        }
    }
    
    public static class BatteryInfo {
        public final int percent;
        public final float voltage;
        public final long updateTime;
        
        public BatteryInfo(int percent, float voltage, long updateTime) {
            this.percent = percent;
            this.voltage = voltage;
            this.updateTime = updateTime;
        }
        
        @Override
        public String toString() {
            return String.format("BatteryInfo{%d%%, %.2fV, updated:%d}", percent, voltage, updateTime);
        }
    }
}