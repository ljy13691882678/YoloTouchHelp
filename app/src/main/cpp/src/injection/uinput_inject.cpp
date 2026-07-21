// uinput_inject.cpp — Thin JNI wrapper over touch_core
// All core logic lives in touch_core.cpp
//
// Anti-detection: uses runtime slot/ID getters instead of compile-time macros.

#include <jni.h>
#include <android/log.h>
#include "touch_core.h"

#define LOG_TAG "UinputInject"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_screen_w = 0;
static int g_screen_h = 0;

// ═════════════════════════════════════════════════════════════════════
//  JNI Interface
//  Package: com.xunlei.ai.service.RemoteInjectorService
// ═════════════════════════════════════════════════════════════════════

extern "C" {

// ─── Configuration ──────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_setDeviceResolution(
    JNIEnv*, jclass, jint, jint) {}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_setScreenResolution(
    JNIEnv*, jclass, jint screenW, jint screenH)
{
    if (screenW > 0 && screenH > 0) {
        g_screen_w = screenW;
        g_screen_h = screenH;
        LOGD("setScreenResolution: %dx%d", screenW, screenH);
    }
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_setScreenRotation(
    JNIEnv*, jclass, jint rotation)
{
    touch_set_screen_params(g_screen_w, g_screen_h, rotation);
    LOGD("setScreenRotation: %d (screen=%dx%d)", rotation, g_screen_w, g_screen_h);
}

// ─── Lifecycle ──────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_openUinputNative(
    JNIEnv*, jobject)
{
    if (touch_init(g_screen_w, g_screen_h)) {
        LOGD("openUinputNative: OK, virtual_slot=%d trigger_slot=%d virtual_id=%d trigger_id=%d",
             touch_get_virtual_slot(), touch_get_trigger_slot(),
             touch_get_virtual_id(), touch_get_trigger_id());
        return touch_get_output_fd();
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_closeUinputNative(
    JNIEnv*, jobject)
{
    touch_close();
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_startGeteventListenerNative(
    JNIEnv*, jobject)
{
    touch_start_readers();
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_stopGeteventListenerNative(
    JNIEnv*, jobject)
{
    touch_stop_readers();
}

// ─── Virtual touch (aim) — uses randomized slot/ID from touch_core ──

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_uinputSendDown(
    JNIEnv*, jobject, jint, jint x, jint y, jint)
{
    if (!touch_is_initialized()) return JNI_FALSE;
    touch_down(touch_get_virtual_slot(), touch_get_virtual_id(), x, y);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_uinputSendMove(
    JNIEnv*, jobject, jint, jint x, jint y, jint)
{
    if (!touch_is_initialized()) return JNI_FALSE;
    touch_move(touch_get_virtual_slot(), x, y);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_uinputSendUp(
    JNIEnv*, jobject, jint, jint)
{
    if (!touch_is_initialized()) return JNI_FALSE;
    touch_up(touch_get_virtual_slot());
    return JNI_TRUE;
}

// ─── Trigger touch — uses randomized slot/ID from touch_core ────────

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_uinputTriggerDown(
    JNIEnv*, jobject, jint x, jint y)
{
    if (!touch_is_initialized()) return;
    touch_down(touch_get_trigger_slot(), touch_get_trigger_id(), x, y);
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_uinputTriggerUp(
    JNIEnv*, jobject)
{
    if (!touch_is_initialized()) return;
    touch_up(touch_get_trigger_slot());
}

// ─── Zone configuration ────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeSetTriggerZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    touch_set_trigger_zone(l, t, r, b);
    LOGD("TriggerZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeIsFingerInTriggerZone(
    JNIEnv*, jclass)
{
    return touch_is_finger_in_trigger_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeSetAdsZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    touch_set_ads_zone(l, t, r, b);
    LOGD("AdsZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeIsFingerInAdsZone(
    JNIEnv*, jclass)
{
    return touch_is_finger_in_ads_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeSetFireZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    touch_set_fire_zone(l, t, r, b);
    LOGD("FireZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeIsFingerInFireZone(
    JNIEnv*, jclass)
{
    return touch_is_finger_in_fire_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeSetJoystickZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    touch_set_joystick_zone(l, t, r, b);
    LOGD("JoystickZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeIsFingerInJoystickZone(
    JNIEnv*, jclass)
{
    return touch_is_finger_in_joystick_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_xunlei_ai_service_RemoteInjectorService_nativeLiftJoystickFinger(
    JNIEnv*, jclass)
{
    return touch_lift_joystick_finger() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"