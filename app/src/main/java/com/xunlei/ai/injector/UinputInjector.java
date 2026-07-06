package com.xunlei.ai.injector;

import android.util.Log;

public class UinputInjector {
    private static final String TAG = "UinputInjector";
    private static volatile UinputInjector instance;

    private int fd = -1;
    private boolean available = false;

    public static UinputInjector getInstance() {
        if (instance == null) {
            synchronized (UinputInjector.class) {
                if (instance == null) {
                    instance = new UinputInjector();
                }
            }
        }
        return instance;
    }

    public boolean init() {
        if (available) return true;
        fd = openUinput();
        if (fd >= 0) {
            available = true;
            Log.d(TAG, "UinputInjector ready, fd=" + fd);
            return true;
        }
        Log.e(TAG, "UinputInjector init failed");
        return false;
    }

    public boolean isAvailable() {
        return available && fd >= 0;
    }

    public boolean tap(int x, int y) {
        if (!isAvailable()) return false;
        return sendTap(x, y);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (!isAvailable()) return false;
        if (!sendTouchDown(x1, y1, 10)) return false;
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            return false;
        }
        return sendTouchUp(10);
    }

    public void keepAlive() {
        // uinput doesn't need keepalive like injectInputEvent does
    }

    public void destroy() {
        if (fd >= 0) {
            closeUinput();
            fd = -1;
            available = false;
        }
    }

    public void setResolution(int screenW, int screenH, int devW, int devH) {
        setDeviceResolutionNative(devW, devH);
        setScreenResolution(screenW, screenH);
        this.screenW = screenW;
        this.screenH = screenH;
        this.devW = devW;
        this.devH = devH;
        Log.d(TAG, "setResolution: screen=" + screenW + "x" + screenH + " device=" + devW + "x" + devH);
    }

    public int getScreenW() { return screenW; }
    public int getScreenH() { return screenH; }
    public int getDevW() { return devW; }
    public int getDevH() { return devH; }

    private int screenW = 2120;
    private int screenH = 3000;
    private int devW = 21199;
    private int devH = 29999;

    // Native methods
    private static native int openUinput();
    private static native void closeUinput();
    private static native boolean sendTouchDown(int x, int y, int pointerId);
    private static native boolean sendTouchMove(int x, int y, int pointerId);
    private static native boolean sendTouchUp(int pointerId);
    private static native boolean sendTap(int x, int y);
    private static native void setDeviceResolutionNative(int devW, int devH);
    private static native void setScreenResolution(int screenW, int screenH);

    static {
        try {
            System.loadLibrary("uinput_inject");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load uinput_inject library: " + e.getMessage());
        }
    }
}
