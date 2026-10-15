package com.droid.xrdesk2.xreal;

/**
 * Single-producer, single-consumer ring of (timestamp, right, up, roll rate) samples, so the
 * USB reader thread can hand samples to the stabilization thread without locks or allocation.
 * When full, new samples are dropped (and counted). Plain Java.
 */
public final class RateRing {
    private final long[] mT;
    private final float[] mRight;
    private final float[] mUp;
    private final float[] mRoll;
    private final int mMask;
    private volatile int mHead;  // written only by the producer
    private volatile int mTail;  // written only by the consumer
    private volatile long mDropped;

    public RateRing(int capacityPowerOfTwo) {
        if (Integer.bitCount(capacityPowerOfTwo) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        mT = new long[capacityPowerOfTwo];
        mRight = new float[capacityPowerOfTwo];
        mUp = new float[capacityPowerOfTwo];
        mRoll = new float[capacityPowerOfTwo];
        mMask = capacityPowerOfTwo - 1;
    }

    /** Producer thread only. Returns false if the ring was full and the sample was dropped. */
    public boolean put(long t, float right, float up, float roll) {
        int head = mHead;
        if (head - mTail > mMask) {
            mDropped++;
            return false;
        }
        int i = head & mMask;
        mT[i] = t;
        mRight[i] = right;
        mUp[i] = up;
        mRoll[i] = roll;
        mHead = head + 1;  // volatile write publishes the slot
        return true;
    }

    public interface Consumer {
        void accept(long t, float right, float up, float roll);
    }

    /** Consumer thread only. Delivers every queued sample in order; returns how many. */
    public int drain(Consumer consumer) {
        int tail = mTail;
        int head = mHead;
        int n = 0;
        while (tail != head) {
            int i = tail & mMask;
            consumer.accept(mT[i], mRight[i], mUp[i], mRoll[i]);
            tail++;
            n++;
        }
        mTail = tail;
        return n;
    }

    /** Consumer thread only. Discards everything queued. */
    public void clear() {
        mTail = mHead;
    }

    public long dropped() {
        return mDropped;
    }
}
