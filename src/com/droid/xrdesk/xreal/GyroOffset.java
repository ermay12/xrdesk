package com.droid.xrdesk.xreal;

/**
 * Run-time gyroscope bias correction: a time-based port of FusionOffset from
 * xioTechnologies/Fusion @b607fc32 Fusion/FusionOffset.c:20-73, which XRLinuxDriver applies to
 * these glasses (xrDeviceKit @586520b4 src/device_imu.c:835-837). Once all axes have stayed
 * under the threshold for the timeout, the offset slowly tracks the reading through a
 * first-order low-pass at the cutoff frequency. Plain Java, single-threaded.
 */
public final class GyroOffset {
    private static final float CUTOFF_HZ = 0.02f;         // FusionOffset.c:20
    private static final float TIMEOUT_S = 5f;            // FusionOffset.c:25
    private static final float THRESHOLD_DEG_S = 3.0f;    // FusionOffset.c:30

    private float mOx;
    private float mOy;
    private float mOz;
    private float mStillSeconds;

    public void reset() {
        mOx = mOy = mOz = 0f;
        mStillSeconds = 0f;
    }

    /** Corrects {@code g} (deg/s, length 3) in place. {@code dt} is the sample interval in seconds. */
    public void update(float[] g, float dt) {
        g[0] -= mOx;
        g[1] -= mOy;
        g[2] -= mOz;
        if (Math.abs(g[0]) > THRESHOLD_DEG_S || Math.abs(g[1]) > THRESHOLD_DEG_S
                || Math.abs(g[2]) > THRESHOLD_DEG_S) {
            mStillSeconds = 0f;
            return;
        }
        if (mStillSeconds < TIMEOUT_S) {
            mStillSeconds += dt;
            return;
        }
        float k = 2f * (float) Math.PI * CUTOFF_HZ * dt;  // FusionOffset.c:41 with dt = 1/sampleRate
        mOx += g[0] * k;
        mOy += g[1] * k;
        mOz += g[2] * k;
    }

    public float offsetX() {
        return mOx;
    }

    public float offsetY() {
        return mOy;
    }

    public float offsetZ() {
        return mOz;
    }
}
