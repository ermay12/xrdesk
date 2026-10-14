package com.droid.xrdesk.motion;

/**
 * Head gestures from rotation rates (plain Java, no Android):
 * <ul>
 * <li>a quick flick switches monitors: up = next / down = previous, or right = next /
 *     left = previous, depending on the swipe preference;</li>
 * <li>a "no" shake (several fast reversals of yaw) opens or closes the menu.</li>
 * </ul>
 * Feed every sample in order from one thread; at most one event is reported per sample. After an
 * event, everything is ignored for the cooldown so the head's return motion doesn't retrigger.
 *
 * <p>Rates are rad/s about the viewing screen's right axis (pitch: positive = tilting up) and its
 * up axis (yaw: positive = turning left), as DesktopActivity delivers them.
 *
 * <p>Shake vs flick: in up/down mode flicks are pitch and the shake is yaw, so they don't
 * conflict. In left/right mode a shake starts like a horizontal flick, so a horizontal flick is
 * only committed once its stroke has ended and {@code commitDelay} has passed without a reversal;
 * a reversal cancels it (that is either a shake starting, or a quick return).
 */
public final class HeadGestures {
    public static final int NONE = 0;
    public static final int NEXT = 1;
    public static final int PREVIOUS = 2;
    public static final int SHAKE = 3;

    // Flicks
    private boolean mHorizontal;
    private boolean mFlicksEnabled = true;
    private float mFlickRate = 2.5f;                      // rad/s; 0 disables flicks
    private float mFlickAngle = (float) Math.toRadians(12);
    private long mFlickWindowNs = 300_000_000L;
    private long mCommitDelayNs = 150_000_000L;          // left/right mode only
    private long mCooldownNs = 800_000_000L;

    // Shake
    private float mShakeRate = 1.5f;                      // rad/s; 0 disables shakes
    private float mShakeStrokeAngle = (float) Math.toRadians(6);
    private int mShakeStrokes = 3;
    private long mShakeWindowNs = 1_000_000_000L;
    private long mShakeGapNs = 300_000_000L;              // max pause between alternating strokes

    // Flick state
    private int mFlickSign;
    private float mFlickSoFar;
    private long mFlickStartNs;
    private boolean mFlickFired;
    private int mPending = NONE;                          // left/right mode: flick awaiting commit
    private int mPendingSign;
    private long mPendingStrokeEndNs = -1;

    // Shake state
    private int mStrokeSign;
    private float mStrokeAngle;
    private long mStrokeStartNs;
    private boolean mStrokeCounted;
    private int mStrokeCount;
    private int mLastStrokeSign;
    private long mLastStrokeEndNs;
    private long mFirstStrokeStartNs;

    private long mCooldownUntilNs = Long.MIN_VALUE;

    /** Next monitor index after a step of +1/-1, wrapping around (so two monitors toggle). */
    public static int step(int active, int count, int delta) {
        if (count <= 1) {
            return 0;
        }
        int next = (active + delta) % count;
        return next < 0 ? next + count : next;
    }

    public void setHorizontal(boolean horizontal) {
        if (horizontal != mHorizontal) {
            mHorizontal = horizontal;
            resetTracking();
        }
    }

    /** Flicks are off with a single monitor and while the menu is open; shakes still work. */
    public void setFlicksEnabled(boolean enabled) {
        if (enabled != mFlicksEnabled) {
            mFlicksEnabled = enabled;
            resetTracking();
        }
    }

    public void setFlick(float rate, float angleRad, long windowNs, long cooldownNs) {
        mFlickRate = rate;
        mFlickAngle = angleRad;
        mFlickWindowNs = windowNs;
        mCooldownNs = cooldownNs;
    }

    public void setCommitDelay(long ns) {
        mCommitDelayNs = ns;
    }

    public void setShake(float rate, float strokeAngleRad, int strokes, long windowNs, long gapNs) {
        mShakeRate = rate;
        mShakeStrokeAngle = strokeAngleRad;
        mShakeStrokes = Math.max(2, strokes);
        mShakeWindowNs = windowNs;
        mShakeGapNs = gapNs;
    }

    /** True while the head's return motion after an event is being ignored. */
    public boolean inCooldown(long t) {
        return t < mCooldownUntilNs;
    }

    /** Forget all partial gestures and any cooldown (e.g. after a pause or a source change). */
    public void reset() {
        mCooldownUntilNs = Long.MIN_VALUE;
        resetTracking();
    }

    /** One sample: time in ns, pitch and yaw rates in rad/s, dt in s. Returns an event or NONE. */
    public int update(long t, float pitch, float yaw, float dt) {
        if (t < mCooldownUntilNs) {
            return NONE;
        }
        if (trackShake(t, yaw, dt)) {
            fired(t);
            return SHAKE;
        }
        // Positive = toward "next": tilting up, or turning right (negative yaw).
        int flick = trackFlick(t, mHorizontal ? -yaw : pitch, dt);
        if (flick != NONE) {
            fired(t);
        }
        return flick;
    }

    private void fired(long t) {
        mCooldownUntilNs = t + mCooldownNs;
        resetTracking();
    }

    private void resetTracking() {
        mFlickSign = 0;
        mFlickSoFar = 0;
        mFlickFired = false;
        mPending = NONE;
        mPendingStrokeEndNs = -1;
        mStrokeSign = 0;
        mStrokeAngle = 0;
        mStrokeCounted = false;
        mStrokeCount = 0;
    }

    private int trackFlick(long t, float v, float dt) {
        if (!mFlicksEnabled || mFlickRate <= 0) {
            return NONE;
        }
        int sign = v >= mFlickRate ? 1 : v <= -mFlickRate ? -1 : 0;
        if (sign != 0 && sign == mFlickSign) {
            mFlickSoFar += Math.abs(v) * dt;
        } else {
            if (mFlickSign != 0 && mPending != NONE && mPendingStrokeEndNs < 0) {
                mPendingStrokeEndNs = t;  // the pending flick's stroke just ended
            }
            mFlickSign = sign;
            mFlickSoFar = sign != 0 ? Math.abs(v) * dt : 0f;
            mFlickStartNs = t;
            mFlickFired = false;
        }
        if (sign != 0 && !mFlickFired && mFlickSoFar >= mFlickAngle && t - mFlickStartNs <= mFlickWindowNs) {
            mFlickFired = true;
            int event = sign > 0 ? NEXT : PREVIOUS;
            if (!mHorizontal) {
                return event;
            }
            mPending = event;
            mPendingSign = sign;
            mPendingStrokeEndNs = -1;
        }
        if (mPending != NONE) {
            float reversalRate = mShakeRate > 0 ? Math.min(mShakeRate, mFlickRate) : mFlickRate;
            if (v * mPendingSign <= -reversalRate) {
                mPending = NONE;  // reversed: a shake starting, or a quick return
                return NONE;
            }
            if (mPendingStrokeEndNs >= 0 && t - mPendingStrokeEndNs >= mCommitDelayNs) {
                int event = mPending;
                mPending = NONE;
                return event;
            }
        }
        return NONE;
    }

    private boolean trackShake(long t, float yaw, float dt) {
        if (mShakeRate <= 0) {
            return false;
        }
        int sign = yaw >= mShakeRate ? 1 : yaw <= -mShakeRate ? -1 : 0;
        if (sign != 0 && sign == mStrokeSign) {
            mStrokeAngle += Math.abs(yaw) * dt;
        } else {
            mStrokeSign = sign;
            mStrokeAngle = sign != 0 ? Math.abs(yaw) * dt : 0f;
            mStrokeStartNs = t;
            mStrokeCounted = false;
        }
        if (sign == 0) {
            return false;
        }
        if (!mStrokeCounted && mStrokeAngle >= mShakeStrokeAngle) {
            mStrokeCounted = true;
            // A full stroke: it continues the sequence if it alternates and follows closely.
            boolean continues = mStrokeCount > 0 && sign == -mLastStrokeSign
                    && mStrokeStartNs - mLastStrokeEndNs <= mShakeGapNs;
            if (!continues) {
                mStrokeCount = 0;
                mFirstStrokeStartNs = mStrokeStartNs;
            }
            mStrokeCount++;
            mLastStrokeSign = sign;
            if (mStrokeCount >= mShakeStrokes && t - mFirstStrokeStartNs <= mShakeWindowNs) {
                return true;
            }
        }
        if (mStrokeCounted) {
            mLastStrokeEndNs = t;  // follows the counted stroke until it ends
        }
        return false;
    }
}
