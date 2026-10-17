package com.droid.xrdesk.motion;

/**
 * Head rotation to a picture offset (plain Java, no Android). The picture slides against small
 * head rotations so it appears to stay put, and always drifts back to center, so it follows the
 * head on larger or slower turns. Feed every sample in order from one thread.
 *
 * <p>Drift curves (how the picture comes back to center once the head stops):
 * <ul>
 * <li>{@link #SMOOTH}: the drift has momentum, like a weight on a critically damped spring. It
 *     starts from rest, speeds up, then eases into center: an S-curve.</li>
 * <li>{@link #EXPONENTIAL}: the offset leaks away at a rate proportional to its size, so the
 *     drift is fastest at the start and creeps at the end.</li>
 * <li>{@link #LINEAR}: constant speed, stopping at center.</li>
 * <li>{@link #ACCELERATE} (default): starts from rest and speeds up steadily, then stops dead at
 *     center.</li>
 * </ul>
 * Every curve heads straight for center. Exponential and smooth treat each direction the same way,
 * so that falls out of doing them separately; linear and accelerate move along the line to center
 * at one speed, because separate constant speeds would set off at 45 degrees and turn a corner.
 * The drift setting is the exponential curve's time constant. Every curve gets halfway back in
 * the same time (drift * ln 2) from the largest offset; smooth and exponential do so from any
 * offset, linear and accelerate come back sooner from smaller ones. Each direction has its own
 * largest offset; linear and accelerate set their speed from the larger of the two.
 *
 * <p>Rates are rad/s about the viewing screen's right axis (positive = tilting up) and up axis
 * (positive = turning left). Offsets are pixels, positive = right / down: turning left slides the
 * picture right, tilting up slides it down.
 */
public final class Stabilizer {
    public static final int SMOOTH = 0;
    public static final int EXPONENTIAL = 1;
    public static final int LINEAR = 2;
    public static final int ACCELERATE = 3;

    private static final float LN2 = 0.693147f;
    // A critically damped spring from rest is halfway back when omega * t = 1.6783, where
    // (1 + x) e^-x = 1/2.
    private static final float SMOOTH_HALFWAY = 1.6783f;

    private float mFocalPx = 2262f;   // on-screen pixels per radian (~46 degree FOV across 1920 px)
    private float mDriftSec = 1f;     // exponential time constant; the other curves match its halfway time
    private float mPredictSec = 0.008f;  // look-ahead covering display latency
    private float mDeadband = 0.004f;  // rad/s; gyro noise below this is ignored
    private int mCurve = ACCELERATE;
    private float mOmega;        // smooth: spring stiffness, 1/s
    private float mSpeed;        // linear: px/s
    private float mAccel;        // accelerate: px/s^2
    private float mMaxSpeed;     // accelerate: speed reached falling from the largest offset
    private float mAccelSpeed;   // accelerate: current speed toward center, px/s

    private final Axis mX = new Axis();
    private final Axis mY = new Axis();

    /** One direction's state. */
    private static final class Axis {
        float max = 600f;  // largest offset, px
        float off;    // px
        float rate;   // head motion, px/s
        float drift;  // smooth: drift velocity, px/s
    }

    public Stabilizer() {
        derive();
    }

    /**
     * Takes effect from the next sample without recentering, so it can be tuned live. maxX and
     * maxY are the largest offsets in pixels: sideways (turning) and up and down (tilting).
     */
    public void configure(float focalPx, float driftSec, float maxX, float maxY, float predictSec, float deadband,
            int curve) {
        mFocalPx = focalPx;
        mDriftSec = Math.max(0.01f, driftSec);
        mX.max = Math.max(0f, maxX);
        mY.max = Math.max(0f, maxY);
        mPredictSec = predictSec;
        mDeadband = deadband;
        if (curve != mCurve) {
            mCurve = curve;
            mX.drift = mY.drift = mAccelSpeed = 0f;
        }
        derive();
    }

    private void derive() {
        float halfway = mDriftSec * LN2;
        float max = Math.max(mX.max, mY.max);
        mOmega = SMOOTH_HALFWAY / halfway;
        mSpeed = max / (2f * halfway);              // max/2 in halfway seconds
        mAccel = max / (halfway * halfway);         // max/2 = a t^2 / 2 at t = halfway
        mMaxSpeed = (float) Math.sqrt(2f * mAccel * max);
    }

    /** Curve constant for a name: smooth | exp | linear | accel. Unknown = accel. */
    public static int curveNamed(String name) {
        if ("smooth".equals(name)) {
            return SMOOTH;
        } else if ("exp".equals(name)) {
            return EXPONENTIAL;
        } else if ("linear".equals(name)) {
            return LINEAR;
        }
        return ACCELERATE;
    }

    /** Back to center, at rest. */
    public void reset() {
        mX.off = mX.rate = mX.drift = 0f;
        mY.off = mY.rate = mY.drift = 0f;
        mAccelSpeed = 0f;
    }

    /** One sample: rates in rad/s about the right and up axes, dt in s since the previous one. */
    public void update(float right, float up, float dt) {
        mX.rate = Math.abs(up) < mDeadband ? 0f : mFocalPx * up;
        mY.rate = Math.abs(right) < mDeadband ? 0f : mFocalPx * right;
        switch (mCurve) {
            case EXPONENTIAL: {
                // Integrate the rotation but leak it away, so the picture always drifts back.
                float decay = (float) Math.exp(-dt / mDriftSec);
                mX.off = clamp(mX.off * decay + mX.rate * dt, mX.max);
                mY.off = clamp(mY.off * decay + mY.rate * dt, mY.max);
                break;
            }
            case SMOOTH:
                spring(mX, dt);
                spring(mY, dt);
                break;
            case LINEAR:
                followHead(dt);
                towardCenter(mSpeed * dt);
                break;
            case ACCELERATE: {
                float beforeX = mX.off;
                float beforeY = mY.off;
                followHead(dt);
                if (beforeX * mX.off + beforeY * mY.off <= 0f) {
                    mAccelSpeed = 0f;  // at center, or the head carried it across: start from rest
                }
                mAccelSpeed = Math.min(mAccelSpeed + mAccel * dt, mMaxSpeed);
                if (towardCenter(mAccelSpeed * dt)) {
                    mAccelSpeed = 0f;  // arrived: stop dead
                }
                break;
            }
            default:
                break;
        }
    }

    /** The spring pulls the drift velocity toward center; damping keeps it from overshooting. */
    private void spring(Axis a, float dt) {
        a.drift += (-mOmega * mOmega * a.off - 2f * mOmega * a.drift) * dt;
        a.off = clamp(a.off + (a.rate + a.drift) * dt, a.max);
    }

    private void followHead(float dt) {
        mX.off = clamp(mX.off + mX.rate * dt, mX.max);
        mY.off = clamp(mY.off + mY.rate * dt, mY.max);
    }

    /** Moves the offset straight toward center by {@code step} px; true if it got there. */
    private boolean towardCenter(float step) {
        float r = (float) Math.hypot(mX.off, mY.off);
        if (r <= step) {
            mX.off = mY.off = 0f;
            return true;
        }
        float k = (r - step) / r;
        mX.off *= k;
        mY.off *= k;
        return false;
    }

    /** Horizontal offset to show now, in whole pixels (keeps text sharp). */
    public int x() {
        return show(mX);
    }

    /** Vertical offset to show now, in whole pixels. */
    public int y() {
        return show(mY);
    }

    /** The offset a little ahead in time, covering display latency (head motion only, plus the spring). */
    private int show(Axis a) {
        float ahead = a.rate + (mCurve == SMOOTH ? a.drift : 0f);
        return Math.round(clamp(a.off + ahead * mPredictSec, a.max));
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
