package com.droid.xrdesk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** Views and colors shared by the app's screens, which are built in code: white on black. */
final class Ui {
    static final int BUTTON_COLOR = 0xFF2A2A2A;   // the big touch areas
    static final int PRESSED_COLOR = 0xFF5A5A5A;
    static final int SECONDARY_TEXT = 0xFFAAAAAA;

    private Ui() {
    }

    static float inches(Context context, float in) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_IN, in, context.getResources().getDisplayMetrics());
    }

    /** White text, as for labels and titles. */
    static TextView text(Context context, String s, int sp) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        return t;
    }

    /** The centered label of a big touch area. */
    static TextView label(Context context, String text) {
        TextView v = text(context, text, 20);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    /** A button in a screen's top bar. */
    static Button menuButton(Context context, String text, View.OnClickListener listener) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    static GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(36f);
        return d;
    }

    /** An equal share of a top bar. */
    static LinearLayout.LayoutParams weightedWidth() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(4, 0, 4, 0);
        return p;
    }

    /**
     * A haptic as hardware feedback: on the mouse and picture screens the phone is the glasses'
     * input device, felt rather than looked at, so it plays even with touch feedback turned off
     * in the phone's settings or the phone on silent. A composition primitive at a scale where
     * the phone has it, else a predefined effect.
     */
    static void buzz(Context context, int primitive, float scale, int fallbackEffect) {
        Vibrator v = context.getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) {
            return;
        }
        buzz(context, v.areAllPrimitivesSupported(primitive)
                ? VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
                : VibrationEffect.createPredefined(fallbackEffect));
    }

    static void buzz(Context context, VibrationEffect effect) {
        Vibrator v = context.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK));
        }
    }

    /** A slider from min to max; onChange gets the value when the user moves it. */
    static SeekBar slider(Context context, int min, int max, IntConsumer onChange) {
        SeekBar s = new SeekBar(context);
        s.setMin(min);
        s.setMax(max);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    onChange.accept(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return s;
    }
}
