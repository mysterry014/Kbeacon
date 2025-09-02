package com.kkmcn.sensordemo.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 비콘별 설정/보정치/배터리 정보 영속화 래퍼
 * - SharedPreferences 기반
 * - MAC/이름 키 병행 저장 (MAC 우선 매칭)
 * - 거리 임계값, 캘리브레이션 매개변수, 배터리 상태 관리
 * 
 * 키 스킴:
 * - beacon.{MAC}.thr / beacon.{NAME}.thr (거리 임계값, 기본 50.0)
 * - beacon.{MAC}.tx1m / beacon.{NAME}.tx1m (txPowerAt1m, 기본 -59.0)
 * - beacon.{MAC}.n / beacon.{NAME}.n (경로손실지수, 기본 2.0)
 * - beacon.{MAC}.batt / beacon.{NAME}.batt (배터리 %)
 * 
 * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI 미사용
 */
public class Prefs {
    private static final String TAG = "Prefs";
    private static final String PREF_NAME = "beacon_settings";
    
    // 기본값 상수들
    private static final double DEFAULT_DISTANCE_THRESHOLD = 50.0;
    private static final double DEFAULT_TX_POWER_AT_1M = -59.0;
    private static final double DEFAULT_PATH_LOSS_EXPONENT = 2.0;
    
    // 키 접두사
    private static final String KEY_PREFIX_BEACON = "beacon.";
    private static final String KEY_SUFFIX_THRESHOLD = ".thr";
    private static final String KEY_SUFFIX_TX_POWER = ".tx1m";
    private static final String KEY_SUFFIX_N = ".n";
    private static final String KEY_SUFFIX_ALIAS = ".alias";  // 별칭 저장용
    private static final String KEY_SUFFIX_BATTERY = ".batt";
    
    private final SharedPreferences mPrefs;
    
    /**
     * Prefs 인스턴스 생성
     * @param context Application context 권장
     */
    public Prefs(Context context) {
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * MAC 주소 정규화 (대문자 + 콜론 제거)
     * @param mac 원본 MAC 주소
     * @return 정규화된 MAC (예: "ABCD12345678")
     */
    private String normalizeMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return "";
        }
        return mac.toUpperCase().replaceAll(":", "");
    }
    
    /**
     * 거리 임계값 조회 (MAC 우선 → 이름 → 기본값)
     * @param mac MAC 주소
     * @param name 비콘 이름
     * @param defaultValue 기본값
     * @return 거리 임계값 (미터)
     */
    public double getDistanceThreshold(String mac, String name, double defaultValue) {
        // MAC 키 우선 시도
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_THRESHOLD;
            String value = mPrefs.getString(macKey, null);
            if (value != null) {
                try {
                    double result = Double.parseDouble(value);
                    Log.d(TAG, "Load threshold by MAC: " + normalizeMac(mac) + " = " + result);
                    return result;
                } catch (NumberFormatException e) {
                    Log.d(TAG, "Invalid threshold value for MAC: " + value);
                }
            }
        }
        
        // 이름 키 시도
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_THRESHOLD;
            String value = mPrefs.getString(nameKey, null);
            if (value != null) {
                try {
                    double result = Double.parseDouble(value);
                    Log.d(TAG, "Load threshold by name: " + name + " = " + result);
                    return result;
                } catch (NumberFormatException e) {
                    Log.d(TAG, "Invalid threshold value for name: " + value);
                }
            }
        }
        
        return defaultValue;
    }
    
    /**
     * 거리 임계값 저장 (MAC/이름 둘 다 저장)
     * @param mac MAC 주소
     * @param name 비콘 이름
     * @param value 거리 임계값 (미터)
     */
    public void setDistanceThreshold(String mac, String name, double value) {
        SharedPreferences.Editor editor = mPrefs.edit();
        String valueStr = String.valueOf(value);
        
        // MAC 키로 저장
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_THRESHOLD;
            editor.putString(macKey, valueStr);
            Log.d(TAG, "Save threshold by MAC: " + normalizeMac(mac) + " = " + value);
        }
        
        // 이름 키로 저장
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_THRESHOLD;
            editor.putString(nameKey, valueStr);
            Log.d(TAG, "Save threshold by name: " + name + " = " + value);
        }
        
        editor.apply();
    }
    
    /**
     * txPowerAt1m 조회 (MAC 우선 → 이름 → 기본값)
     * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI 미사용
     */
    public double getTxPowerAt1m(String mac, String name, double defaultValue) {
        // MAC 키 우선 시도
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_TX_POWER;
            String value = mPrefs.getString(macKey, null);
            if (value != null) {
                try {
                    double result = Double.parseDouble(value);
                    Log.d(TAG, "Load txPower by MAC: " + normalizeMac(mac) + " = " + result);
                    return result;
                } catch (NumberFormatException e) {
                    Log.d(TAG, "Invalid txPower value for MAC: " + value);
                }
            }
        }
        
        // 이름 키 시도
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_TX_POWER;
            String value = mPrefs.getString(nameKey, null);
            if (value != null) {
                try {
                    double result = Double.parseDouble(value);
                    Log.d(TAG, "Load txPower by name: " + name + " = " + result);
                    return result;
                } catch (NumberFormatException e) {
                    Log.d(TAG, "Invalid txPower value for name: " + value);
                }
            }
        }
        
        return defaultValue;
    }
    
    /**
     * txPowerAt1m 저장 (MAC/이름 둘 다 저장)
     * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI 미사용
     */
    public void setTxPowerAt1m(String mac, String name, double value) {
        SharedPreferences.Editor editor = mPrefs.edit();
        String valueStr = String.valueOf(value);
        
        // MAC 키로 저장
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_TX_POWER;
            editor.putString(macKey, valueStr);
            Log.d(TAG, "Save txPower by MAC: " + normalizeMac(mac) + " = " + value);
        }
        
        // 이름 키로 저장
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_TX_POWER;
            editor.putString(nameKey, valueStr);
            Log.d(TAG, "Save txPower by name: " + name + " = " + value);
        }
        
        editor.apply();
    }
    
    /**
     * 경로손실지수(n) 조회 (MAC 우선 → 이름 → 기본값)
     * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI 미사용
     */
    public double getN(String mac, String name, double defaultValue) {
        // MAC 키 우선 시도
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_N;
            String value = mPrefs.getString(macKey, null);
            if (value != null) {
                try {
                    double result = Double.parseDouble(value);
                    Log.d(TAG, "Load pathLoss by MAC: " + normalizeMac(mac) + " = " + result);
                    return result;
                } catch (NumberFormatException e) {
                    Log.d(TAG, "Invalid pathLoss value for MAC: " + value);
                }
            }
        }
        
        // 이름 키 시도
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_N;
            String value = mPrefs.getString(nameKey, null);
            if (value != null) {
                try {
                    double result = Double.parseDouble(value);
                    Log.d(TAG, "Load pathLoss by name: " + name + " = " + result);
                    return result;
                } catch (NumberFormatException e) {
                    Log.d(TAG, "Invalid pathLoss value for name: " + value);
                }
            }
        }
        
        return defaultValue;
    }
    
    /**
     * 경로손실지수(n) 저장 (MAC/이름 둘 다 저장)
     * Note: 광고 RSSI 기준으로 설계됨, 연결 중 RSSI 미사용
     */
    public void setN(String mac, String name, double value) {
        SharedPreferences.Editor editor = mPrefs.edit();
        String valueStr = String.valueOf(value);
        
        // MAC 키로 저장
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_N;
            editor.putString(macKey, valueStr);
            Log.d(TAG, "Save pathLoss by MAC: " + normalizeMac(mac) + " = " + value);
        }
        
        // 이름 키로 저장
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_N;
            editor.putString(nameKey, valueStr);
            Log.d(TAG, "Save pathLoss by name: " + name + " = " + value);
        }
        
        editor.apply();
    }
    
    /**
     * 배터리 % 조회 (MAC 우선 → 이름 → 기본값)
     * @param mac MAC 주소
     * @param name 비콘 이름
     * @param defaultValue 기본값 (null 허용)
     * @return 배터리 % (null이면 값 없음)
     */
    public Integer getBatteryPct(String mac, String name, Integer defaultValue) {
        // MAC 키 우선 시도
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_BATTERY;
            int value = mPrefs.getInt(macKey, -1);
            if (value >= 0) {
                Log.d(TAG, "Load battery by MAC: " + normalizeMac(mac) + " = " + value + "%");
                return value;
            }
        }
        
        // 이름 키 시도
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_BATTERY;
            int value = mPrefs.getInt(nameKey, -1);
            if (value >= 0) {
                Log.d(TAG, "Load battery by name: " + name + " = " + value + "%");
                return value;
            }
        }
        
        return defaultValue;
    }
    
    /**
     * 배터리 % 저장 (MAC/이름 둘 다 저장)
     * @param mac MAC 주소
     * @param name 비콘 이름
     * @param value 배터리 % (0-100)
     */
    public void setBatteryPct(String mac, String name, int value) {
        SharedPreferences.Editor editor = mPrefs.edit();
        
        // MAC 키로 저장
        if (mac != null && !mac.isEmpty()) {
            String macKey = KEY_PREFIX_BEACON + normalizeMac(mac) + KEY_SUFFIX_BATTERY;
            editor.putInt(macKey, value);
            Log.d(TAG, "Save battery by MAC: " + normalizeMac(mac) + " = " + value + "%");
        }
        
        // 이름 키로 저장
        if (name != null && !name.isEmpty()) {
            String nameKey = KEY_PREFIX_BEACON + name + KEY_SUFFIX_BATTERY;
            editor.putInt(nameKey, value);
            Log.d(TAG, "Save battery by name: " + name + " = " + value + "%");
        }
        
        editor.apply();
    }
    
    /**
     * 기본값 상수들 반환
     */
    public static double getDefaultDistanceThreshold() {
        return DEFAULT_DISTANCE_THRESHOLD;
    }
    
    public static double getDefaultTxPowerAt1m() {
        return DEFAULT_TX_POWER_AT_1M;
    }
    
    public static double getDefaultPathLossExponent() {
        return DEFAULT_PATH_LOSS_EXPONENT;
    }
    
    /**
     * 별칭(alias) 조회 - 광고 이름과 분리된 로컬 이름
     * @param mac MAC 주소
     * @return 별칭 (null이면 별칭 없음)
     */
    public String getAlias(String mac) {
        if (mac == null || mac.isEmpty()) {
            return null;
        }
        
        String normalizedMac = mac.toUpperCase().replaceAll(":", "");
        String key = KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_ALIAS;
        return mPrefs.getString(key, null);
    }
    
    /**
     * 별칭(alias) 저장
     * @param mac MAC 주소
     * @param alias 별칭 (빈 문자열이면 삭제)
     */
    public void setAlias(String mac, String alias) {
        if (mac == null || mac.isEmpty()) {
            return;
        }
        
        String normalizedMac = mac.toUpperCase().replaceAll(":", "");
        String key = KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_ALIAS;
        
        SharedPreferences.Editor editor = mPrefs.edit();
        if (alias == null || alias.trim().isEmpty()) {
            editor.remove(key);
            Log.d(TAG, "Remove alias for MAC: " + mac);
        } else {
            editor.putString(key, alias.trim());
            Log.d(TAG, "Save alias for MAC: " + mac + " = " + alias.trim());
        }
        editor.apply();
    }
    
    /**
     * 특정 비콘의 모든 설정 제거
     * @param mac MAC 주소
     * @param name 비콘 이름
     */
    public void clearBeaconSettings(String mac, String name) {
        SharedPreferences.Editor editor = mPrefs.edit();
        
        if (mac != null && !mac.isEmpty()) {
            String macPrefix = KEY_PREFIX_BEACON + normalizeMac(mac);
            editor.remove(macPrefix + KEY_SUFFIX_THRESHOLD);
            editor.remove(macPrefix + KEY_SUFFIX_TX_POWER);
            editor.remove(macPrefix + KEY_SUFFIX_N);
            editor.remove(macPrefix + KEY_SUFFIX_BATTERY);
            Log.d(TAG, "Clear settings by MAC: " + normalizeMac(mac));
        }
        
        if (name != null && !name.isEmpty()) {
            String namePrefix = KEY_PREFIX_BEACON + name;
            editor.remove(namePrefix + KEY_SUFFIX_THRESHOLD);
            editor.remove(namePrefix + KEY_SUFFIX_TX_POWER);
            editor.remove(namePrefix + KEY_SUFFIX_N);
            editor.remove(namePrefix + KEY_SUFFIX_BATTERY);
            Log.d(TAG, "Clear settings by name: " + name);
        }
        
        editor.apply();
    }
    
    // ============ 캘리브레이션 결과 저장/복원 API ============
    
    /**
     * 캘리브레이션 결과 데이터 클래스
     */
    public static class CalibrationParams {
        public final double txPowerAt1m;
        public final double pathLossExponent;
        public final double rSquared;
        public final double rmse;
        public final double maxResidual;
        public final long timestampMs;
        
        public CalibrationParams(double txPowerAt1m, double pathLossExponent, 
                               double rSquared, double rmse, double maxResidual, long timestampMs) {
            this.txPowerAt1m = txPowerAt1m;
            this.pathLossExponent = pathLossExponent;
            this.rSquared = rSquared;
            this.rmse = rmse;
            this.maxResidual = maxResidual;
            this.timestampMs = timestampMs;
        }
    }
    
    // 캘리브레이션 키 접미사
    private static final String KEY_SUFFIX_CAL_TX1M = ".cal_tx1m";
    private static final String KEY_SUFFIX_CAL_N = ".cal_n";
    private static final String KEY_SUFFIX_CAL_R2 = ".cal_r2";
    private static final String KEY_SUFFIX_CAL_RMSE = ".cal_rmse";
    private static final String KEY_SUFFIX_CAL_MAX_RESIDUAL = ".cal_maxres";
    private static final String KEY_SUFFIX_CAL_TS = ".cal_ts";
    
    /**
     * 캘리브레이션 결과 저장 (MAC 기준)
     * 키 설계: cal_tx1m_{mac}, cal_n_{mac}, cal_r2_{mac}, cal_rmse_{mac}, cal_maxres_{mac}, cal_ts_{mac}
     * 
     * @param mac MAC 주소
     * @param txPowerAt1m 1m 기준 RSSI
     * @param pathLossExponent 경로 손실 지수
     * @param rSquared R² 값
     * @param rmse RMSE 값
     * @param maxResidual 최대 잔차 값
     * @param timestampMs 측정 시간
     */
    public void saveCalibration(String mac, double txPowerAt1m, double pathLossExponent, 
                               double rSquared, double rmse, double maxResidual, long timestampMs) {
        if (mac == null || mac.isEmpty()) {
            Log.w(TAG, "Cannot save calibration: MAC is null or empty");
            return;
        }
        
        String normalizedMac = normalizeMac(mac);
        SharedPreferences.Editor editor = mPrefs.edit();
        
        // 캘리브레이션 전용 키로 저장
        editor.putString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_TX1M, String.valueOf(txPowerAt1m));
        editor.putString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_N, String.valueOf(pathLossExponent));
        editor.putString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_R2, String.valueOf(rSquared));
        editor.putString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_RMSE, String.valueOf(rmse));
        editor.putString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_MAX_RESIDUAL, String.valueOf(maxResidual));
        editor.putLong(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_TS, timestampMs);
        
        // 실제 사용되는 tx1m, n 값도 동시에 업데이트
        editor.putString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_TX_POWER, String.valueOf(txPowerAt1m));
        editor.putString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_N, String.valueOf(pathLossExponent));
        
        editor.apply();
        
        Log.i(TAG, String.format("Saved calibration for MAC %s: tx1m=%.2f, n=%.2f, R²=%.2f, RMSE=%.2f, maxRes=%.2f", 
               normalizedMac, txPowerAt1m, pathLossExponent, rSquared, rmse, maxResidual));
    }
    
    /**
     * 캘리브레이션 결과 로드 (MAC 기준)
     * @param mac MAC 주소
     * @return 캘리브레이션 파라미터 (null이면 저장된 결과 없음)
     */
    public CalibrationParams loadCalibration(String mac) {
        if (mac == null || mac.isEmpty()) {
            return null;
        }
        
        String normalizedMac = normalizeMac(mac);
        
        try {
            String tx1mStr = mPrefs.getString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_TX1M, null);
            String nStr = mPrefs.getString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_N, null);
            String r2Str = mPrefs.getString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_R2, null);
            String rmseStr = mPrefs.getString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_RMSE, null);
            String maxResStr = mPrefs.getString(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_MAX_RESIDUAL, null);
            long timestamp = mPrefs.getLong(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_TS, 0);
            
            if (tx1mStr == null || nStr == null || r2Str == null || rmseStr == null || timestamp == 0) {
                return null; // 불완전한 데이터
            }
            
            double txPowerAt1m = Double.parseDouble(tx1mStr);
            double pathLossExponent = Double.parseDouble(nStr);
            double rSquared = Double.parseDouble(r2Str);
            double rmse = Double.parseDouble(rmseStr);
            double maxResidual = (maxResStr != null) ? Double.parseDouble(maxResStr) : 0.0; // 기존 데이터 호환성
            
            Log.d(TAG, String.format("Loaded calibration for MAC %s: tx1m=%.2f, n=%.2f, R²=%.2f, RMSE=%.2f, maxRes=%.2f", 
                   normalizedMac, txPowerAt1m, pathLossExponent, rSquared, rmse, maxResidual));
            
            return new CalibrationParams(txPowerAt1m, pathLossExponent, rSquared, rmse, maxResidual, timestamp);
            
        } catch (NumberFormatException e) {
            Log.w(TAG, "Failed to load calibration for MAC " + normalizedMac + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 캘리브레이션 결과 삭제
     * @param mac MAC 주소
     */
    public void clearCalibration(String mac) {
        if (mac == null || mac.isEmpty()) {
            return;
        }
        
        String normalizedMac = normalizeMac(mac);
        SharedPreferences.Editor editor = mPrefs.edit();
        
        editor.remove(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_TX1M);
        editor.remove(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_N);
        editor.remove(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_R2);
        editor.remove(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_RMSE);
        editor.remove(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_MAX_RESIDUAL);
        editor.remove(KEY_PREFIX_BEACON + normalizedMac + KEY_SUFFIX_CAL_TS);
        
        editor.apply();
        
        Log.d(TAG, "Cleared calibration for MAC: " + normalizedMac);
    }
    
    // TODO: 추후 확장 포인트
    // - 배치 저장/로드 최적화
    // - JSON 기반 백업/복원
    // - 통계 정보 추가 (최근 업데이트 시간 등)
    // - 마이그레이션 지원
}