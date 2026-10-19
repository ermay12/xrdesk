package com.droid.xrdesk;

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

    // ---- Mouse (oneway, in order) ----

    void mouseShow(boolean show) {
        Parcel data = start();
        data.writeBoolean(show);
        oneway(ProjectionHelper.MOUSE_SHOW, data);
    }

    void mouseMove(float x, float y) {
        Parcel data = start();
        data.writeFloat(x);
        data.writeFloat(y);
        oneway(ProjectionHelper.MOUSE_MOVE, data);
    }

    void mouseButton(int button, boolean down) {
        Parcel data = start();
        data.writeInt(button);
        data.writeBoolean(down);
        oneway(ProjectionHelper.MOUSE_BUTTON, data);
    }

    void mouseScroll(float notches) {
        Parcel data = start();
        data.writeFloat(notches);
        oneway(ProjectionHelper.MOUSE_SCROLL, data);
    }

    /** Typing mode: the glasses' keyboard shows on the phone. Returns false if that failed. */
    boolean keyboardOnPhone(boolean on) {
        Parcel data = start();
        Parcel reply = Parcel.obtain();
        try {
            data.writeBoolean(on);
            mBinder.transact(ProjectionHelper.KEYBOARD_ON_PHONE, data, reply, 0);
            reply.readException();
            return reply.readBoolean();
        } catch (RemoteException | RuntimeException e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** The keyboard's top edge on the phone's display, px, or -1 while no keyboard is showing. */
    int keyboardTop() {
        Parcel data = start();
        Parcel reply = Parcel.obtain();
        try {
            mBinder.transact(ProjectionHelper.KEYBOARD_TOP, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Moves Android's USB permission dialog to a display once it opens (it may open on the glasses). */
    void usbDialogTo(int displayId) {
        Parcel data = start();
        data.writeInt(displayId);
        oneway(ProjectionHelper.USB_DIALOG_TO, data);
    }

    /** Gives input focus to a display's top task. */
    void focusDisplay(int displayId) {
        Parcel data = start();
        data.writeInt(displayId);
        oneway(ProjectionHelper.FOCUS_DISPLAY, data);
    }

    private static Parcel start() {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(ProjectionHelper.DESCRIPTOR);
        return data;
    }

    private void oneway(int code, Parcel data) {
        try {
            mBinder.transact(code, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | RuntimeException ignored) {
            // The helper died; the service notices through its Shizuku connection.
        } finally {
            data.recycle();
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
