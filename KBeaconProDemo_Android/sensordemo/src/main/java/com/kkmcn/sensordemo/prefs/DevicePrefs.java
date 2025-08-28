package com.kkmcn.sensordemo.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

public class DevicePrefs {
    private static final String PREF = "device_prefs";
    private static final String KEY_PAIRED = "paired_macs";

    // per-MAC keys
    private static String K(String mac, String suffix){ return "mac:" + mac + ":" + suffix; }

    public static Set<String> getPaired(Context c){
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return new HashSet<>(sp.getStringSet(KEY_PAIRED, new HashSet<>()));
    }

    public static void addPaired(Context c, String mac){
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Set<String> cur = new HashSet<>(sp.getStringSet(KEY_PAIRED, new HashSet<>()));
        cur.add(mac);
        sp.edit().putStringSet(KEY_PAIRED, cur).apply();
    }

    public static void removePaired(Context c, String mac){
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        Set<String> cur = new HashSet<>(sp.getStringSet(KEY_PAIRED, new HashSet<>()));
        cur.remove(mac);
        sp.edit().putStringSet(KEY_PAIRED, cur).apply();
    }

    // Calibration constants
    public static void setCalibration(Context c, String mac, float txPowerAt1m, float n){
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(K(mac,"tx1m"), txPowerAt1m)
            .putFloat(K(mac,"n"), n)
            .apply();
    }
    public static float getTxPower1m(Context c, String mac, float def){ return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat(K(mac,"tx1m"), def); }
    public static float getPathLossN(Context c, String mac, float def){ return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat(K(mac,"n"), def); }

    // Distance threshold (meters)
    public static void setDistanceThreshold(Context c, String mac, float meters){
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(K(mac,"distThr"), meters)
            .apply();
    }
    public static float getDistanceThreshold(Context c, String mac, float def){
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat(K(mac,"distThr"), def);
    }

    // Battery level (% or raw)
    public static void setBattery(Context c, String mac, int battery){
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(K(mac,"batt"), battery)
            .apply();
    }
    public static int getBattery(Context c, String mac, int def){
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(K(mac,"batt"), def);
    }
}