package com.xunlei.ai.service;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.Random;

import com.xunlei.ai.IRemoteInjector;

public class RemoteInjectorService extends IRemoteInjector.Stub {
    private static final String TAG = "RemoteInjector";
    private static final int INJECT_MODE_ASYNC = 0;
    private static final int INJECT_MODE_WAIT = 1;

    private Method injectMethod;
    private Object inputManager;
    private static int uinputFd = -1;
    private static int dev_abs_max_x = 21199;
    private static int dev_abs_max_y = 29999;
    private static int screen_w = 2120;
    private static int screen_h = 3000;
    private static int screen_rotation = 0;

    public volatile boolean available = false;
    public static final int INPUT_METHOD_UINPUT = 0;
    public static final int INPUT_METHOD_INPUT_MANAGER = 1;
    private int inputMethod = INPUT_METHOD_UINPUT;
    long bgDownTime;

    // ── Anti-detection: randomized per-session parameters ──
    private final Random rng = new Random();
    private final int bgId;           // randomized background finger ID (3-8)
    private float bgX, bgY;           // jittered background position
    private int lastTapId = -1;
    private int drawingPointerId = -1;
    private boolean pointerDown = false;
    private static final int TRIGGER_PTR_ID = 20;
    private boolean triggerPointerDown = false;
    private long triggerDownTime = 0;
    private IBinder.DeathRecipient clientDeathRecipient;

    public static RemoteInjectorService instance;

    public RemoteInjectorService() {
        // Randomize background finger ID: 3-8 (avoids 0-2 used by system, and 9-10 too fixed)
        bgId = 3 + rng.nextInt(6);
        // Randomize initial background position: 3-9 pixel range
        bgX = 3f + rng.nextFloat() * 6f;
        bgY = 3f + rng.nextFloat() * 6f;
    }

    public void setResolution(int sw, int sh, int dw, int dh) {
        screen_w = sw; screen_h = sh;
        setScreenResolution(sw, sh);
        setScreenRotation(screen_rotation);
    }

    public void setOrientationConfig(int rotation) {
        screen_rotation = rotation;
        setScreenRotation(rotation);
    }

    public void startGeteventListener() { startGeteventListenerNative(); }
    public void stopGeteventListener() { stopGeteventListenerNative(); }

    private MotionEvent.PointerProperties ptr(int id) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = id; p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        return p;
    }

    private MotionEvent.PointerCoords coord(float x, float y) {
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x; c.y = y;
        // Add realistic pressure and touch size to reduce detection
        c.pressure = 0.55f + rng.nextFloat() * 0.35f;      // 0.55~0.90
        c.size = 0.08f + rng.nextFloat() * 0.04f;           // 0.08~0.12
        c.touchMajor = 20f + rng.nextFloat() * 15f;         // 20~35
        c.touchMinor = 18f + rng.nextFloat() * 12f;         // 18~30
        return c;
    }

    /** Jitter background position slightly each time to avoid fixed (5,5) pattern */
    private MotionEvent.PointerCoords bgCoord() {
        // Micro-jitter: background moves ±2px slowly over time
        bgX += (rng.nextFloat() - 0.5f) * 0.6f;
        bgY += (rng.nextFloat() - 0.5f) * 0.6f;
        // Clamp to reasonable range
        bgX = Math.max(2f, Math.min(10f, bgX));
        bgY = Math.max(2f, Math.min(10f, bgY));
        return coord(bgX, bgY);
    }

    /** Pick a random pointer ID (5-12) different from the last one */
    private int nextTapId() {
        int id;
        do {
            id = 5 + rng.nextInt(8);  // 5~12
        } while (id == lastTapId || id == bgId);
        lastTapId = id;
        return id;
    }

    /** Randomized tap delay: 5-18ms (realistic human touch range) */
    private int randTapDelay() {
        return 5 + rng.nextInt(14);
    }

    public void onCreate() {
        instance = this;
        Log.d(TAG, "RemoteInjectorService onCreate, pid=" + Process.myPid() + " bgId=" + bgId);
    }

    private int openUinput() { return openUinputNative(); }

    private void closeUinput() {
        if (uinputFd >= 0) { closeUinputNative(); uinputFd = -1; }
    }

    @Override
    public void setInputMethod(int method) {
        inputMethod = method;
        Log.d(TAG, "setInputMethod: " + (method == INPUT_METHOD_UINPUT ? "Uinput" : "InputManager"));
    }

    @SuppressLint("PrivateApi")
    public boolean init() {
        if (available) return true;
        Log.d(TAG, "init: starting, pid=" + Process.myPid() + " method=" + (inputMethod == INPUT_METHOD_UINPUT ? "Uinput" : "InputManager"));

        if (inputMethod == INPUT_METHOD_UINPUT) {
            try {
                uinputFd = openUinputNative();
                Log.d(TAG, "init: openUinputNative returned fd=" + uinputFd);
                if (uinputFd >= 0) {
                    bgDownTime = SystemClock.uptimeMillis();
                    available = true;
                    Log.d(TAG, "init: RemoteInjectorService ready with uinput, pid=" + Process.myPid());
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "init: uinput exception: " + e.getMessage());
            }
        }

        try {
            Method getInstance = android.hardware.input.InputManager.class.getMethod("getInstance");
            android.hardware.input.InputManager inputMan = (android.hardware.input.InputManager) getInstance.invoke(null);
            Method injectInputEvent = android.hardware.input.InputManager.class.getMethod(
                "injectInputEvent", InputEvent.class, int.class);

            bgDownTime = SystemClock.uptimeMillis();
            MotionEvent bgDown = MotionEvent.obtain(bgDownTime, bgDownTime, MotionEvent.ACTION_DOWN, 1,
                new MotionEvent.PointerProperties[]{ptr(bgId)}, new MotionEvent.PointerCoords[]{bgCoord()},
                0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            injectInputEvent.invoke(inputMan, bgDown, INJECT_MODE_ASYNC);
            bgDown.recycle();

            inputManager = inputMan;
            injectMethod = injectInputEvent;
            bgDownTime = SystemClock.uptimeMillis();
            available = true;
            Log.d(TAG, "RemoteInjector ready via injectInputEvent, pid=" + Process.myPid() + " bgId=" + bgId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "init: injectInputEvent failed: " + e.getMessage());
        }

        return false;
    }

    public void keepAlive() {
        if (!available) return;
        if (uinputFd >= 0) {
            uinputSendMove(uinputFd, (int)bgX, (int)bgY, bgId);
        } else if (inputManager != null && injectMethod != null) {
            try {
                MotionEvent m = MotionEvent.obtain(bgDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1,
                    new MotionEvent.PointerProperties[]{ptr(bgId)}, new MotionEvent.PointerCoords[]{bgCoord()},
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, m, INJECT_MODE_ASYNC);
                m.recycle();
            } catch (Exception e) { Log.e(TAG, "keepAlive: " + e.getMessage()); }
        }
    }

    public void tap(int x, int y) throws android.os.RemoteException {
        if (!available) return;
        try {
            long now = SystemClock.uptimeMillis();
            int tapId = nextTapId();
            int delay = randTapDelay();

            if (uinputFd >= 0) {
                uinputSendDown(uinputFd, x, y, tapId);
                try { Thread.sleep(delay); } catch (InterruptedException e) {}
                uinputSendUp(uinputFd, tapId);
            } else {
                int shift = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                MotionEvent.PointerCoords bgC = bgCoord();
                MotionEvent.PointerCoords targetC = coord(x, y);
                MotionEvent down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN | shift, 2,
                    new MotionEvent.PointerProperties[]{ptr(bgId), ptr(tapId)},
                    new MotionEvent.PointerCoords[]{bgC, targetC},
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                MotionEvent up = MotionEvent.obtain(bgDownTime, now + delay, MotionEvent.ACTION_POINTER_UP | shift, 2,
                    new MotionEvent.PointerProperties[]{ptr(bgId), ptr(tapId)},
                    new MotionEvent.PointerCoords[]{bgC, targetC},
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, down, INJECT_MODE_ASYNC);
                injectMethod.invoke(inputManager, up, INJECT_MODE_ASYNC);
                down.recycle(); up.recycle();
            }
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void swipe(int x1, int y1, int x2, int y2, int durationMs) throws android.os.RemoteException {
        if (!available) return;
        try {
            long now = SystemClock.uptimeMillis();
            int tapId = nextTapId();

            if (uinputFd >= 0) {
                if (!pointerDown && x1 == x2 && y1 == y2) {
                    drawingPointerId = tapId;
                    uinputSendDown(uinputFd, x1, y1, drawingPointerId);
                    pointerDown = true;
                } else if (pointerDown) {
                    uinputSendMove(uinputFd, x2, y2, drawingPointerId);
                } else {
                    uinputSendDown(uinputFd, x1, y1, tapId);
                    try { Thread.sleep(durationMs); } catch (InterruptedException e) {}
                    uinputSendMove(uinputFd, x2, y2, tapId);
                    uinputSendUp(uinputFd, tapId);
                }
            } else {
                int shift = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                if (!pointerDown && x1 == x2 && y1 == y2) {
                    drawingPointerId = tapId;
                    pointerDown = true;
                    MotionEvent.PointerCoords bgC = bgCoord();
                    MotionEvent down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN | shift, 2,
                        new MotionEvent.PointerProperties[]{ptr(bgId), ptr(drawingPointerId)},
                        new MotionEvent.PointerCoords[]{bgC, coord(x1, y1)},
                        0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    injectMethod.invoke(inputManager, down, INJECT_MODE_ASYNC);
                    down.recycle();
                } else if (pointerDown) {
                    MotionEvent.PointerCoords bgC = bgCoord();
                    MotionEvent move = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_MOVE, 2,
                        new MotionEvent.PointerProperties[]{ptr(bgId), ptr(drawingPointerId)},
                        new MotionEvent.PointerCoords[]{bgC, coord(x2, y2)},
                        0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    injectMethod.invoke(inputManager, move, INJECT_MODE_ASYNC);
                    move.recycle();
                } else {
                    MotionEvent.PointerCoords bgC = bgCoord();
                    MotionEvent down = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_POINTER_DOWN | shift, 2,
                        new MotionEvent.PointerProperties[]{ptr(bgId), ptr(tapId)},
                        new MotionEvent.PointerCoords[]{bgC, coord(x1, y1)},
                        0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    MotionEvent move = MotionEvent.obtain(bgDownTime, now + durationMs, MotionEvent.ACTION_MOVE, 2,
                        new MotionEvent.PointerProperties[]{ptr(bgId), ptr(tapId)},
                        new MotionEvent.PointerCoords[]{bgC, coord(x2, y2)},
                        0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    int upDelay = 3 + rng.nextInt(6);
                    MotionEvent up = MotionEvent.obtain(bgDownTime, now + durationMs + upDelay, MotionEvent.ACTION_POINTER_UP | shift, 2,
                        new MotionEvent.PointerProperties[]{ptr(bgId), ptr(tapId)},
                        new MotionEvent.PointerCoords[]{bgC, coord(x2, y2)},
                        0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                    injectMethod.invoke(inputManager, down, INJECT_MODE_ASYNC);
                    injectMethod.invoke(inputManager, move, INJECT_MODE_ASYNC);
                    injectMethod.invoke(inputManager, up, INJECT_MODE_ASYNC);
                    down.recycle(); move.recycle(); up.recycle();
                }
            }
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void moveTo(int x, int y) throws android.os.RemoteException {
        if (!available || !pointerDown) return;
        try {
            if (uinputFd >= 0) {
                uinputSendMove(uinputFd, x, y, drawingPointerId);
            } else {
                long now = SystemClock.uptimeMillis();
                MotionEvent.PointerCoords bgC = bgCoord();
                MotionEvent move = MotionEvent.obtain(bgDownTime, now, MotionEvent.ACTION_MOVE, 2,
                    new MotionEvent.PointerProperties[]{ptr(bgId), ptr(drawingPointerId)},
                    new MotionEvent.PointerCoords[]{bgC, coord(x, y)},
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, move, INJECT_MODE_ASYNC);
                move.recycle();
            }
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void lift() throws android.os.RemoteException {
        if (!available || !pointerDown) return;
        try {
            if (uinputFd >= 0) {
                uinputSendUp(uinputFd, drawingPointerId);
            } else {
                long now = SystemClock.uptimeMillis();
                int upDelay = 3 + rng.nextInt(6);
                MotionEvent.PointerCoords bgC = bgCoord();
                MotionEvent up = MotionEvent.obtain(bgDownTime, now + upDelay, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
                    new MotionEvent.PointerProperties[]{ptr(bgId), ptr(drawingPointerId)},
                    new MotionEvent.PointerCoords[]{bgC, bgC},
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, up, INJECT_MODE_ASYNC);
                up.recycle();
            }
            pointerDown = false;
            drawingPointerId = -1;
        } catch (Exception e) {
            throw new android.os.RemoteException(e.getMessage());
        }
    }

    public void aimAt(int targetX, int targetY, int centerX, int centerY, float speed, int screenW, int screenH) throws android.os.RemoteException {
        if (!available) return;
        double dx = targetX - centerX;
        double dy = targetY - centerY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1) return;
        int duration = Math.min(50, (int) (dist / speed));
        swipe(centerX, centerY, centerX + (int)dx, centerY + (int)dy, duration);
    }

    @Override
    public void triggerDown(int x, int y) throws android.os.RemoteException {
        if (!available) return;
        if (uinputFd >= 0) {
            uinputTriggerDown(x, y);
        } else if (injectMethod != null && inputManager != null) {
            if (triggerPointerDown) return;
            try {
                long now = SystemClock.uptimeMillis();
                triggerDownTime = now;
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
                    new MotionEvent.PointerProperties[]{ptr(TRIGGER_PTR_ID)},
                    new MotionEvent.PointerCoords[]{coord(x, y)},
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, down, INJECT_MODE_ASYNC);
                down.recycle();
                triggerPointerDown = true;
                Log.d(TAG, "triggerDown at (" + x + "," + y + ")");
            } catch (Exception e) {
                Log.e(TAG, "triggerDown error: " + e.getMessage());
            }
        }
    }

    @Override
    public void triggerUp() throws android.os.RemoteException {
        if (!available) return;
        if (uinputFd >= 0) {
            uinputTriggerUp();
        } else if (injectMethod != null && inputManager != null && triggerPointerDown) {
            try {
                long now = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(triggerDownTime, now, MotionEvent.ACTION_UP, 1,
                    new MotionEvent.PointerProperties[]{ptr(TRIGGER_PTR_ID)},
                    new MotionEvent.PointerCoords[]{coord(0f, 0f)},
                    0, 0, 0.8f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                injectMethod.invoke(inputManager, up, INJECT_MODE_ASYNC);
                up.recycle();
                triggerPointerDown = false;
                Log.d(TAG, "triggerUp");
            } catch (Exception e) {
                Log.e(TAG, "triggerUp error: " + e.getMessage());
            }
        }
    }

    @Override
    public void triggerTap(int x, int y, int durationMs) throws android.os.RemoteException {
        if (!available) return;
        Log.d(TAG, "triggerTap at (" + x + "," + y + ") dur=" + durationMs);
        triggerDown(x, y);
        try { Thread.sleep(durationMs); } catch (InterruptedException e) {}
        triggerUp();
    }

    @Override
    public void setTriggerZone(int left, int top, int right, int bottom) throws android.os.RemoteException {
        nativeSetTriggerZone(left, top, right, bottom);
        Log.d(TAG, "setTriggerZone: (" + left + "," + top + ")-(" + right + "," + bottom + ")");
    }

    @Override
    public boolean isFingerInTriggerZone() throws android.os.RemoteException {
        return nativeIsFingerInTriggerZone();
    }

    @Override
    public void setAdsZone(int left, int top, int right, int bottom) throws android.os.RemoteException {
        nativeSetAdsZone(left, top, right, bottom);
        Log.d(TAG, "setAdsZone: (" + left + "," + top + ")-(" + right + "," + bottom + ")");
    }

    @Override
    public boolean isFingerInAdsZone() throws android.os.RemoteException {
        return nativeIsFingerInAdsZone();
    }

    @Override
    public void setFireZone(int left, int top, int right, int bottom) throws android.os.RemoteException {
        nativeSetFireZone(left, top, right, bottom);
        Log.d(TAG, "setFireZone: (" + left + "," + top + ")-(" + right + "," + bottom + ")");
    }

    @Override
    public boolean isFingerInFireZone() throws android.os.RemoteException {
        return nativeIsFingerInFireZone();
    }

    @Override
    public void setJoystickZone(int left, int top, int right, int bottom) throws android.os.RemoteException {
        nativeSetJoystickZone(left, top, right, bottom);
        Log.d(TAG, "setJoystickZone: (" + left + "," + top + ")-(" + right + "," + bottom + ")");
    }

    @Override
    public boolean isFingerInJoystickZone() throws android.os.RemoteException {
        return nativeIsFingerInJoystickZone();
    }

    @Override
    public boolean liftJoystickFinger() throws android.os.RemoteException {
        return nativeLiftJoystickFinger();
    }

    public void linkToDeath(IBinder token) {
        if (token == null) return;
        try {
            clientDeathRecipient = () -> {
                Log.w(TAG, "Client process died, cleaning up...");
                destroy();
            };
            token.linkToDeath(clientDeathRecipient, 0);
            Log.d(TAG, "linkToDeath registered");
        } catch (RemoteException e) {
            Log.e(TAG, "linkToDeath failed: " + e.getMessage());
        }
    }

    public void destroy() {
        Log.d(TAG, "destroy called");
        if (clientDeathRecipient != null) {
            clientDeathRecipient = null;
        }
        available = false;
        closeUinput();
        inputManager = null;
        injectMethod = null;
    }

    public void blockPhysicalTouch() { Log.d(TAG, "blockPhysicalTouch: handled by native reader"); }
    public void unblockPhysicalTouch() {}

    @Override
    public boolean isAvailable() { return available; }

    private static native int openUinputNative();
    private static native void closeUinputNative();
    private static native void uinputSendDown(int fd, int x, int y, int pointerId);
    private static native void uinputSendMove(int fd, int x, int y, int pointerId);
    private static native void uinputSendUp(int fd, int pointerId);
    private static native void uinputTriggerDown(int x, int y);
    private static native void uinputTriggerUp();
    private static native void nativeSetTriggerZone(int left, int top, int right, int bottom);
    private static native boolean nativeIsFingerInTriggerZone();
    private static native void nativeSetAdsZone(int left, int top, int right, int bottom);
    private static native boolean nativeIsFingerInAdsZone();
    private static native void nativeSetFireZone(int left, int top, int right, int bottom);
    private static native boolean nativeIsFingerInFireZone();
    private static native void nativeSetJoystickZone(int left, int top, int right, int bottom);
    private static native boolean nativeIsFingerInJoystickZone();
    private static native boolean nativeLiftJoystickFinger();
    private static native void setDeviceResolution(int devW, int devH);
    private static native void setScreenResolution(int screenW, int screenH);
    private static native void setScreenRotation(int rotation);
    private static native void startGeteventListenerNative();
    private static native void stopGeteventListenerNative();

    static {
        try {
            System.loadLibrary("uinput_inject");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load uinput_inject library: " + e.getMessage());
        }
    }
}