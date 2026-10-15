package com.droid.xrdesk2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.droid.xrdesk2.motion.Stabilizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings, saved as one "key=value key=value" string (see xrdesk2.sh for the list):
 *   adb shell am start -n com.droid.xrdesk2/.MainActivity --es options "drift=0.3 max=400"
 * The app's sliders and curve choice change drift, max and curve in the same string.
 */
final class Options {
    private static final String TAG = "XRDesk2";
    private static final String PREFS = "xrdesk2";
    private static final String KEY = "options";

    final float focal;      // on-screen pixels per radian of head rotation
    final float pointer;    // mouse screen: cursor pixels per radian of phone rotation
    final float drift;      // seconds to drift back to center
    final int curve;        // drift curve, a Stabilizer constant; saved as smooth | exp | linear | accel
    final float max;        // largest offset, px
    final float predictMs;  // look-ahead covering display latency
    final float deadband;   // rad/s of rotation ignored as noise
    final float fill;       // picture size as a fraction of the display (0.5-1); < 1 leaves room to move
    final int display;      // display id to stabilize; -1 = the first external display
    final boolean swapAxes; // fix-ups for the IMU mounting
    final boolean invertX;
    final boolean invertY;
    final String text;

    private Options(String text) {
        this.text = text;
        Map<String, String> m = parse(text);
        focal = f(m, "focal", 2262f);
        pointer = f(m, "pointer", focal);  // default: the cursor follows where the phone points
        drift = f(m, "drift", 1f);
        curve = Stabilizer.curveNamed(m.get("curve"));
        max = f(m, "max", 600f);
        predictMs = f(m, "predict", 8f);
        deadband = f(m, "deadband", 0.004f);
        fill = f(m, "fill", 1f);
        display = (int) f(m, "display", -1);
        swapAxes = f(m, "imuswap", 0) != 0;
        invertX = f(m, "imuinvx", 0) != 0;
        invertY = f(m, "imuinvy", 0) != 0;
    }

    static Options load(Context context) {
        return new Options(prefs(context).getString(KEY, ""));
    }

    static void save(Context context, String text) {
        prefs(context).edit().putString(KEY, text.trim()).apply();
        Log.i(TAG, "saved options: " + text.trim());
    }

    /** Sets one key, keeping the others. */
    static void put(Context context, String key, String value) {
        Map<String, String> m = parse(load(context).text);
        m.put(key, value);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(sb.length() == 0 ? "" : " ").append(e.getKey()).append('=').append(e.getValue());
        }
        prefs(context).edit().putString(KEY, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : text.trim().split("\\s+")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && eq < kv.length() - 1) {
                m.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return m;
    }

    private static float f(Map<String, String> m, String key, float def) {
        String v = m.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            Log.w(TAG, "bad option " + key + "=" + v);
            return def;
        }
    }
}
