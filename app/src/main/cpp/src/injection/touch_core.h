// touch_core.h — Shared touch injection API
// Used by both JNI (Shizuku) and root_daemon (su)
//
// Anti-detection: virtual slot/ID values are randomized per session.
// Use the getter functions instead of hardcoded macros for live sessions.

#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Default slot/ID constants (used as fallback; actual values randomized per session)
#define TOUCH_VIRTUAL_SLOT  8
#define TOUCH_TRIGGER_SLOT  9
#define TOUCH_VIRTUAL_ID    1000
#define TOUCH_TRIGGER_ID    2000

// Lifecycle
bool touch_init(int screenW, int screenH);
void touch_close(void);
bool touch_is_initialized(void);
int  touch_get_output_fd(void);

// Reader threads (for zone detection)
void touch_start_readers(void);
void touch_stop_readers(void);

// Configuration
void touch_set_screen_params(int w, int h, int rotation);

// Injection (screen coordinates — 4-way rotation handled internally)
void touch_down(int slot, int id, int screenX, int screenY);
void touch_move(int slot, int screenX, int screenY);
void touch_up(int slot);

// ── Anti-detection: randomized slot & finger ID getters ──
// These return the actual per-session values (not the compile-time macros)
int  touch_get_virtual_slot(void);
int  touch_get_virtual_id(void);
int  touch_get_trigger_slot(void);
int  touch_get_trigger_id(void);

// Zone configuration (screen coordinates)
void touch_set_trigger_zone(int l, int t, int r, int b);
void touch_set_ads_zone(int l, int t, int r, int b);
void touch_set_fire_zone(int l, int t, int r, int b);
void touch_set_joystick_zone(int l, int t, int r, int b);

// Zone queries
bool touch_is_finger_in_trigger_zone(void);
bool touch_is_finger_in_ads_zone(void);
bool touch_is_finger_in_fire_zone(void);
bool touch_is_finger_in_joystick_zone(void);

// Lift physical finger in joystick zone
bool touch_lift_joystick_finger(void);

#ifdef __cplusplus
}
#endif