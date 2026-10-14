package com.droid.xrdesk.motion;

/**
 * Roll smoothing (plain Java): integrates the roll rate with the same leaky drift-back-to-level
 * approach the slide uses, so the desktop counter-rotates against quick head tilts and then
 * levels out again. Tiny angles snap to exactly 0 so the layer can return to the cheap,
 * unrotated composition path.
 *
 * <p>Angles are radians, positive = clockwise on screen (y down). A positive roll rate (the head
 * or phone turning counter-clockwise as seen by the viewer, i.e. the Android/RUB convention)
 * makes the world appear to turn clockwise, so the desktop turns clockwise by the same angle.
 */
public final class RollFilter {
    private float mDeadband = 0.004f;
    private float mDriftSec = 0.5f;
    private float mMaxAngle = (float) Math.toRadians(15);
    private float mSnap = 0.0005f;
    private float mAngle;
    private float mRate;

    /**
     * @param deadband rad/s below which the rate counts as still
     * @param driftSec time constant for leveling out
     * @param maxAngle largest rotation, rad
     * @param snap     angles below this (rad) become exactly 0, and angles are quantized to it
     */
    public void configure(float deadband, float driftSec, float maxAngle, float snap) {
        mDeadband = deadband;
        mDriftSec = driftSec;
        mMaxAngle = maxAngle;
        mSnap = snap;
    }

    /** Snap size so that the desktop's corners move less than half a pixel. */
    public static float snapFor(int width, int height) {
        double halfDiagonal = Math.hypot(width, height) / 2;
        return halfDiagonal > 0 ? (float) (0.5 / halfDiagonal) : 0.001f;
    }

    public void reset() {
        mAngle = 0f;
        mRate = 0f;
    }

    public void update(float rate, float dt) {
        mRate = Math.abs(rate) < mDeadband ? 0f : rate;
        float decay = mDriftSec > 0 ? (float) Math.exp(-dt / mDriftSec) : 0f;
        mAngle = clamp(mAngle * decay + mRate * dt, mMaxAngle);
        if (mRate == 0f && Math.abs(mAngle) < mSnap) {
            mAngle = 0f;
        }
    }

    public float angle() {
        return mAngle;
    }

    /**
     * The angle to show: looked ahead by {@code predictSec} and quantized to the snap size, so a
     * still or nearly level desktop is exactly 0 and tiny changes don't cause a transaction.
     */
    public float output(float predictSec) {
        float a = clamp(mAngle + mRate * predictSec, mMaxAngle);
        if (mSnap <= 0f) {
            return a;
        }
        long steps = Math.round(a / mSnap);
        return steps == 0 ? 0f : steps * mSnap;
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
