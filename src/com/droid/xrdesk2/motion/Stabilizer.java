package com.droid.xrdesk2.motion;

/**
 * Head rotation to a picture offset (plain Java, no Android). The picture slides against small
 * head rotations so it appears to stay put, and always drifts back to center, so it follows the
 * head on larger or slower turns. Feed every sample in order from one thread.
 *
 * <p>Rates are rad/s about the viewing screen's right axis (positive = tilting up) and up axis
 * (positive = turning left). Offsets are pixels, positive = right / down: turning left slides the
 * picture right, tilting up slides it down.
 */
public final class Stabilizer {
    private float mFocalPx = 2262f;   // on-screen pixels per radian (~46 degree FOV across 1920 px)
    private float mDriftSec = 1f;     // time constant for drifting back to center
    private float mMaxOffset = 600f;  // largest offset in pixels
    private float mPredictSec = 0.008f;  // look-ahead covering display latency
    private float mDeadband = 0.004f;  // rad/s; gyro noise below this is ignored

    private float mOffX;
    private float mOffY;
    private float mRateX;
    private float mRateY;

    public void configure(float focalPx, float driftSec, float maxOffset, float predictSec, float deadband) {
        mFocalPx = focalPx;
        mDriftSec = Math.max(0.01f, driftSec);
        mMaxOffset = Math.max(0f, maxOffset);
        mPredictSec = predictSec;
        mDeadband = deadband;
        reset();
    }

    /** Back to center, at rest. */
    public void reset() {
        mOffX = mOffY = mRateX = mRateY = 0f;
    }

    /** One sample: rates in rad/s about the right and up axes, dt in s since the previous one. */
    public void update(float right, float up, float dt) {
        mRateX = Math.abs(up) < mDeadband ? 0f : mFocalPx * up;
        mRateY = Math.abs(right) < mDeadband ? 0f : mFocalPx * right;
        // Integrate the rotation but leak it away, so the picture always drifts back to center.
        float decay = (float) Math.exp(-dt / mDriftSec);
        mOffX = clamp(mOffX * decay + mRateX * dt, mMaxOffset);
        mOffY = clamp(mOffY * decay + mRateY * dt, mMaxOffset);
    }

    /** Horizontal offset to show now, in whole pixels (keeps text sharp). */
    public int x() {
        return Math.round(clamp(mOffX + mRateX * mPredictSec, mMaxOffset));
    }

    /** Vertical offset to show now, in whole pixels. */
    public int y() {
        return Math.round(clamp(mOffY + mRateY * mPredictSec, mMaxOffset));
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
