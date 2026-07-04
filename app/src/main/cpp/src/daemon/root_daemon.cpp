/*
 * root_daemon.cpp — Thin stdin/stdout command parser over touch_core.
 * Runs under `su`, communicates via text protocol.
 * All uinput logic lives in touch_core.cpp.
 *
 * Protocol:
 *   Commands (stdin, one per line):
 *     SET_RESOLUTION <screenW> <screenH>
 *     SET_DEVICE_RESOLUTION <devW> <devH>
 *     SET_ORIENTATION <0|1|2|3>      (Surface rotation)
 *     OPEN_UINPUT
 *     CLOSE_UINPUT
 *     START_GETEVENT
 *     STOP_GETEVENT
 *     DOWN <x> <y>
 *     MOVE <x> <y>
 *     UP
 *     TRIGGER_DOWN <x> <y>
 *     TRIGGER_UP
 *     SET_TRIGGER_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_ZONE
 *     SET_ADS_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_ADS_ZONE
 *     SET_FIRE_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_FIRE_ZONE
 *     SET_JOYSTICK_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_JOYSTICK_ZONE
 *     LIFT_JOYSTICK_FINGER
 *     KEEP_ALIVE
 *     DESTROY
 *
 *   Responses (stdout, one per line):
 *     OK
 *     OK:<value>
 *     ERR:<message>
 *
 *   All debug/log output goes to stderr only.
 */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <signal.h>
#include <time.h>
#include <sys/prctl.h>
#include <unistd.h>
#include "touch_core.h"

// Screen params (set via commands before OPEN_UINPUT)
static int g_screen_w = 0;
static int g_screen_h = 0;
static volatile int g_running = 1;

// =========================================================================
// Command handler
// =========================================================================

static void handle_command(const char* cmd) {
    char buf[1024];
    strncpy(buf, cmd, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    char* nl = strchr(buf, '\n');
    if (nl) *nl = '\0';
    char* cr = strchr(buf, '\r');
    if (cr) *cr = '\0';

    if (strncmp(buf, "SET_RESOLUTION ", 15) == 0) {
        int w, h;
        if (sscanf(buf + 15, "%d %d", &w, &h) == 2 && w > 0 && h > 0) {
            g_screen_w = w;
            g_screen_h = h;
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strncmp(buf, "SET_DEVICE_RESOLUTION ", 22) == 0) {
        // Device resolution is auto-detected by touch_core, ignore
        puts("OK");
    }
    else if (strncmp(buf, "SET_ORIENTATION ", 16) == 0) {
        int rotation = atoi(buf + 16);
        touch_set_screen_params(g_screen_w, g_screen_h, rotation);
        puts("OK");
    }
    else if (strcmp(buf, "OPEN_UINPUT") == 0) {
        if (touch_init(g_screen_w, g_screen_h)) {
            int fd = touch_get_output_fd();
            printf("OK:%d\n", fd);
        } else {
            puts("ERR:open failed");
        }
    }
    else if (strcmp(buf, "CLOSE_UINPUT") == 0) {
        touch_close();
        puts("OK");
    }
    else if (strcmp(buf, "START_GETEVENT") == 0) {
        touch_start_readers();
        puts("OK");
    }
    else if (strcmp(buf, "STOP_GETEVENT") == 0) {
        touch_stop_readers();
        puts("OK");
    }
    else if (strncmp(buf, "DOWN ", 5) == 0) {
        int x, y;
        if (sscanf(buf + 5, "%d %d", &x, &y) == 2) {
            touch_down(TOUCH_VIRTUAL_SLOT, TOUCH_VIRTUAL_ID, x, y);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strncmp(buf, "MOVE ", 5) == 0) {
        int x, y;
        if (sscanf(buf + 5, "%d %d", &x, &y) == 2) {
            touch_move(TOUCH_VIRTUAL_SLOT, x, y);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "UP") == 0) {
        touch_up(TOUCH_VIRTUAL_SLOT);
        puts("OK");
    }
    else if (strncmp(buf, "TRIGGER_DOWN ", 13) == 0) {
        int x, y;
        if (sscanf(buf + 13, "%d %d", &x, &y) == 2) {
            touch_down(TOUCH_TRIGGER_SLOT, TOUCH_TRIGGER_ID, x, y);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "TRIGGER_UP") == 0) {
        touch_up(TOUCH_TRIGGER_SLOT);
        puts("OK");
    }
    else if (strncmp(buf, "SET_TRIGGER_ZONE ", 17) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 17, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            touch_set_trigger_zone(l, t, r, b);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_ZONE") == 0) {
        printf("OK:%d\n", touch_is_finger_in_trigger_zone() ? 1 : 0);
    }
    else if (strncmp(buf, "SET_ADS_ZONE ", 13) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 13, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            touch_set_ads_zone(l, t, r, b);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_ADS_ZONE") == 0) {
        printf("OK:%d\n", touch_is_finger_in_ads_zone() ? 1 : 0);
    }
    else if (strncmp(buf, "SET_FIRE_ZONE ", 14) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 14, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            touch_set_fire_zone(l, t, r, b);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_FIRE_ZONE") == 0) {
        printf("OK:%d\n", touch_is_finger_in_fire_zone() ? 1 : 0);
    }
    else if (strncmp(buf, "SET_JOYSTICK_ZONE ", 18) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 18, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            touch_set_joystick_zone(l, t, r, b);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_JOYSTICK_ZONE") == 0) {
        printf("OK:%d\n", touch_is_finger_in_joystick_zone() ? 1 : 0);
    }
    else if (strcmp(buf, "LIFT_JOYSTICK_FINGER") == 0) {
        printf("OK:%d\n", touch_lift_joystick_finger() ? 1 : 0);
    }
    else if (strcmp(buf, "KEEP_ALIVE") == 0) {
        puts("OK");
    }
    else if (strcmp(buf, "DESTROY") == 0) {
        touch_close();
        puts("OK");
        g_running = 0;
    }
    else if (strlen(buf) == 0) {
        // Ignore empty lines
    }
    else {
        fprintf(stderr, "Unknown command: %s\n", buf);
        puts("ERR:unknown command");
    }
    fflush(stdout);
}

// =========================================================================
// Main
// =========================================================================

int main() {
    // 进程名伪装：随机选择一个系统进程名，避免被检测为root_daemon
    static const char* fake_process_names[] = {
        "system_suspend_d",
        "kworker/u8:2",
        "thermal-engine",
        "msm_irqbalance",
        "netd",
        "perfd",
        "lmkd",
        "servicemanager"
    };
    static const int name_count = sizeof(fake_process_names) / sizeof(fake_process_names[0]);
    srand(time(NULL) ^ getpid());
    int name_idx = rand() % name_count;
    prctl(PR_SET_NAME, fake_process_names[name_idx], 0, 0, 0);
    
    signal(SIGPIPE, SIG_IGN);
    setvbuf(stdout, NULL, _IOLBF, 0);

    puts("READY");
    fflush(stdout);

    char line[1024];
    while (g_running) {
        if (fgets(line, sizeof(line), stdin) == NULL) {
            break;
        }
        handle_command(line);
    }

    touch_close();
    return 0;
}
