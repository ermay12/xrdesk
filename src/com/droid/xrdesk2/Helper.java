package com.droid.xrdesk2;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Client side of ProjectionHelper's raw Binder protocol. Calls may come from any thread. */
final class Helper {
    private final IBinder mBinder;

    Helper(IBinder binder) {
        mBinder = binder;
    }

    /** Result of attach: an error, or the display that will be stabilized. */
    static final class Attached {
        String error;
        int displayId = -1;
        String name;
    }

    /** The helper restores the display if {@code client} dies. displayId -1 = the first external display. */
    Attached attach(IBinder client, int displayId, float fill) {
        Attached result = new Attached();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProjectionHelper.DESCRIPTOR);
            data.writeStrongBinder(client);
            data.writeInt(displayId);
            data.writeFloat(fill);
            mBinder.transact(ProjectionHelper.ATTACH, data, reply, 0);
            reply.readException();
            result.error = reply.readString();
            result.displayId = reply.readInt();
            result.name = reply.readString();
        } catch (RemoteException | RuntimeException e) {
            result.error = "the shell helper failed: " + e;
        } finally {
            data.recycle();
            reply.recycle();
        }
        return result;
    }

    /** Shifts the picture; doesn't wait for the helper. */
    void move(int dx, int dy) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProjectionHelper.DESCRIPTOR);
            data.writeInt(dx);
            data.writeInt(dy);
            mBinder.transact(ProjectionHelper.MOVE, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | RuntimeException ignored) {
            // The helper died; the service notices through its Shizuku connection.
        } finally {
            data.recycle();
        }
    }

    /** Re-reads the display's base projection after a display change. Returns an error or null. */
    String refresh() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProjectionHelper.DESCRIPTOR);
            mBinder.transact(ProjectionHelper.REFRESH, data, reply, 0);
            reply.readException();
            return reply.readString();
        } catch (RemoteException | RuntimeException e) {
            return e.toString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Puts the display back as it was; the helper then exits. */
    void detach() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProjectionHelper.DESCRIPTOR);
            mBinder.transact(ProjectionHelper.DETACH, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
