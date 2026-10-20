package com.droid.xrdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.droid.xrdesk.motion.Stabilizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings, saved as one "key=value key=value" string (see xrdesk.sh for the list):
 *   adb shell am start -n com.droid.xrdesk/.MainActivity --es options "drift=0.3 max=400"
 * The app's settings screens change drift, maxyaw, maxpitch, curve, fill, offsetx, offsety,
 * pointerx, pointery, scroll and scrollinv in the same string.
 */
final class Options {
    private static final String TAG = "XRDesk";
    private static final String PREFS = "xrdesk";
    private static final String KEY = "options";
    static final int MIN_FILL = 50;    // picture size, % of the display
    static final int MAX_FILL = 200;   // where MAX_OFFSET pans exactly to the picture's edges
    static final int MAX_OFFSET = 50;  // picture position, % of the display: its center at the edge

    final float focal;      // on-screen pixels per radian of head rotation
    final float pointerX;   // mouse screen: cursor pixels per radian the phone turns sideways
    final float pointerY;   // and tilts up or down
    final float scrollSpeed;     // mouse screen: wheel notches per notch of finger travel on the scroll strip
    final boolean scrollInvert;  // true: drag down to scroll up, like a touch screen
    final float drift;      // seconds to drift back to center
    final int curve;        // drift curve, a Stabilizer constant; saved as smooth | exp | linear | accel
    final float maxYaw;     // largest offset, px: sideways (turning the head)
    final float maxPitch;   // and up and down (tilting it)
    final float predictMs;  // look-ahead covering display latency
    final float deadband;   // rad/s of rotation ignored as noise
    final float fill;       // picture size as a fraction of the display (0.5-2); < 1 leaves room to move, > 1 zooms in
    final float offsetX;    // picture position, % of the display's width from center (+ = right)
    final float offsetY;    // and % of its height (+ = down); both up to MAX_OFFSET either way
    final int display;      // display id to stabilize; -1 = the first external display
    final boolean swapAxes; // fix-ups for the IMU mounting
    final boolean invertX;
    final boolean invertY;
    final String text;

    private Options(String text) {
        this.text = text;
        Map<String, String> m = parse(text);
        focal = f(m, "focal", 2262f);
        float pointer = f(m, "pointer", focal);  // default: the cursor follows where the phone points
        pointerX = f(m, "pointerx", pointer);
        pointerY = f(m, "pointery", pointer);
        scrollSpeed = f(m, "scroll", 1f);
        scrollInvert = f(m, "scrollinv", 0) != 0;
        drift = f(m, "drift", 1f);
        curve = Stabilizer.curveNamed(m.get("curve"));
        float max = f(m, "max", 600f);
        maxYaw = f(m, "maxyaw", max);
        maxPitch = f(m, "maxpitch", max);
        predictMs = f(m, "predict", 8f);
        deadband = f(m, "deadband", 0.004f);
        fill = Math.max(MIN_FILL / 100f, Math.min(MAX_FILL / 100f, f(m, "fill", 1f)));
        offsetX = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, f(m, "offsetx", 0f)));
        offsetY = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, f(m, "offsety", 0f)));
        display = (int) f(m, "display", -1);
        swapAxes = f(m, "imuswap", 0) != 0;
        invertX = f(m, "imuinvx", 0) != 0;
        invertY = f(m, "imuinvy", 0) != 0;
    }

    /** The picture isn't the display's size or is off center, which holds even while not stabilizing. */
    boolean adjusted() {
        return fill != 1f || offsetX != 0f || offsetY != 0f;
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
