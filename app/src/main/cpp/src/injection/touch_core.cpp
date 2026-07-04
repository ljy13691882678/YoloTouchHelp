// touch_core.cpp — Core touch injection logic
// Based on native_touch.cpp + reader threads from TouchHelperA
// Shared by JNI (Shizuku) and root_daemon (su)

#include "touch_core.h"
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <array>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <mutex>
#include <vector>

#ifdef ANDROID
#include <android/log.h>
#define LOG_TAG "TouchCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) do { fprintf(stderr, "D/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#define LOGE(...) do { fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#endif

// ─── Constants ───────────────────────────────────────────────────────

static constexpr int maxE = 5;
static constexpr int maxF = 10;
static constexpr int UNGRAB = 0;
static constexpr int GRAB = 1;

// ─── Data structures ────────────────────────────────────────────────

struct Vec2 {
    float x = 0.0f, y = 0.0f;
    Vec2() = default;
    Vec2(float px, float py) : x(px), y(py) {}
    Vec2 operator*(const Vec2& o) const { return {x * o.x, y * o.y}; }
};

struct TouchObj {
    Vec2 pos{};
    int id = 0;
    bool isDown = false;
};

struct Device {
    int fd = 0;
    float s2tx = 1.0f;
    float s2ty = 1.0f;
    input_absinfo absX{};
    input_absinfo absY{};
    TouchObj fingers[maxF]{};
};

struct Zone {
    int l = 0, t = 0, r = 0, b = 0;
    volatile int finger_inside = 0;
};

struct InputBuffer {
    input_event event[512]{};
};

// ─── Global state ────────────────────────────────────────────────────

static std::vector<Device> g_devices;
static std::array<std::array<bool, maxF>, maxE> g_uploadedFingerDown{};
static InputBuffer g_inputBuffer{};
static Vec2 g_touchScale{1.0f, 1.0f};
static Vec2 g_screenSize{};
static std::mutex g_mutex;
static int g_outputFd = 0;
static bool g_initialized = false;

// Screen params
static int g_screen_w = 0, g_screen_h = 0;
static int g_rotation = 0;

// Reader threads
static std::vector<pthread_t> g_reader_threads;
static volatile bool g_running = false;

// Detection zones
static Zone g_trigger_zone;
static Zone g_ads_zone;
static Zone g_fire_zone;
static Zone g_joystick_zone;

// ─── Helpers ─────────────────────────────────────────────────────────

static void genRandomString(char* str, int len) {
    srand(static_cast<unsigned>(time(nullptr)) + len);
    for (int i = 0; i < len - 1; ++i) {
        int flag = rand() % 3;
        if (flag == 0)      str[i] = static_cast<char>('A' + rand() % 26);
        else if (flag == 1) str[i] = static_cast<char>('a' + rand() % 26);
        else                str[i] = static_cast<char>('0' + rand() % 10);
    }
    str[len - 1] = '\0';
}

static void pushEvent(int& count, unsigned short type, unsigned short code, int value) {
    if (count >= static_cast<int>(std::size(g_inputBuffer.event))) return;
    g_inputBuffer.event[count].type = type;
    g_inputBuffer.event[count].code = code;
    g_inputBuffer.event[count].value = value;
    ++count;
}

static bool pointInZone(const Zone& z, int sx, int sy) {
    return z.l < z.r && z.t < z.b &&
           sx >= z.l && sx <= z.r && sy >= z.t && sy <= z.b;
}

static void touchToScreen(float devX, float devY, int touchMaxX, int touchMaxY, int& sx, int& sy);

static bool isTrackedPhysicalFinger(size_t deviceIndex, int fingerIndex) {
    if (deviceIndex >= g_devices.size() || fingerIndex < 0 || fingerIndex >= maxF) {
        return false;
    }
    const TouchObj& finger = g_devices[deviceIndex].fingers[fingerIndex];
    if (!finger.isDown) {
        return false;
    }
    // Mirror the helper behavior: ignore injected fingers and only judge real fingers.
    if (deviceIndex == 0 && (fingerIndex == TOUCH_VIRTUAL_SLOT || fingerIndex == TOUCH_TRIGGER_SLOT)) {
        return false;
    }
    return true;
}

static bool isAnyPhysicalFingerInZoneLocked(const Zone& zone) {
    if (!g_initialized || g_devices.empty()) {
        return false;
    }
    if (zone.l >= zone.r || zone.t >= zone.b) {
        return false;
    }

    const int touchMaxX = std::max(1, g_devices[0].absX.maximum);
    const int touchMaxY = std::max(1, g_devices[0].absY.maximum);

    for (size_t deviceIndex = 0; deviceIndex < g_devices.size(); ++deviceIndex) {
        for (int fingerIndex = 0; fingerIndex < maxF; ++fingerIndex) {
            if (!isTrackedPhysicalFinger(deviceIndex, fingerIndex)) {
                continue;
            }

            const TouchObj& finger = g_devices[deviceIndex].fingers[fingerIndex];
            int sx = 0;
            int sy = 0;
            touchToScreen(finger.pos.x, finger.pos.y, touchMaxX, touchMaxY, sx, sy);
            if (pointInZone(zone, sx, sy)) {
                return true;
            }
        }
    }
    return false;
}

static int normalizeRotation(int rotation) {
    int normalized = rotation % 4;
    if (normalized < 0) normalized += 4;
    return normalized;
}

// Screen → portrait touch coords (4-way rotation + scale)
static void screenToTouch(int sx, int sy, float& tx, float& ty) {
    float px = static_cast<float>(sx);
    float py = static_cast<float>(sy);
    switch (normalizeRotation(g_rotation)) {
    case 1: // Surface.ROTATION_90
        px = static_cast<float>(g_screen_h - sy);
        py = static_cast<float>(sx);
        break;
    case 2: // Surface.ROTATION_180
        px = static_cast<float>(g_screen_w - sx);
        py = static_cast<float>(g_screen_h - sy);
        break;
    case 3: // Surface.ROTATION_270
        px = static_cast<float>(sy);
        py = static_cast<float>(g_screen_w - sx);
        break;
    default: // Surface.ROTATION_0
        break;
    }
    tx = px * g_touchScale.x;
    ty = py * g_touchScale.y;
}

static void touchToScreen(float devX, float devY, int touchMaxX, int touchMaxY, int& sx, int& sy) {
    (void)touchMaxX;
    (void)touchMaxY;

    const float scaleX = std::max(g_touchScale.x, 0.0001f);
    const float scaleY = std::max(g_touchScale.y, 0.0001f);
    const float px = devX / scaleX;
    const float py = devY / scaleY;

    float rawScreenX = px;
    float rawScreenY = py;
    switch (normalizeRotation(g_rotation)) {
    case 1: // Surface.ROTATION_90
        rawScreenX = py;
        rawScreenY = static_cast<float>(g_screen_h) - px;
        break;
    case 2: // Surface.ROTATION_180
        rawScreenX = static_cast<float>(g_screen_w) - px;
        rawScreenY = static_cast<float>(g_screen_h) - py;
        break;
    case 3: // Surface.ROTATION_270
        rawScreenX = static_cast<float>(g_screen_w) - py;
        rawScreenY = px;
        break;
    default: // Surface.ROTATION_0
        break;
    }

    sx = std::clamp(static_cast<int>(std::lround(rawScreenX)), 0, std::max(0, g_screen_w));
    sy = std::clamp(static_cast<int>(std::lround(rawScreenY)), 0, std::max(0, g_screen_h));
}

// ─── Upload (from native_touch.cpp) ─────────────────────────────────

// 人类化触摸：随机数生成（简单的线性同余生成器）
static unsigned int g_human_seed = 12345;
static inline int humanRand() {
    g_human_seed = g_human_seed * 1103515245 + 12345;
    return (g_human_seed / 65536) % 32768;
}
static inline float humanRandFloat() {
    return humanRand() / 32768.0f;
}

static void upload() {
    if (g_outputFd <= 0) return;
    int count = 0;
    int activeFingerCount = 0;
    bool hasActiveFinger = false;
    size_t deviceCount = std::min(g_devices.size(), static_cast<size_t>(maxE));

    for (size_t di = 0; di < deviceCount; ++di) {
        for (int fi = 0; fi < maxF; ++fi) {
            const TouchObj& finger = g_devices[di].fingers[fi];
            bool wasUploaded = g_uploadedFingerDown[di][fi];
            int slot = static_cast<int>(di * maxF + fi);
            
            // 深度伪装：slot限制在10个以内
            if (slot >= 10) continue;

            if (finger.isDown) {
                hasActiveFinger = true;
                ++activeFingerCount;
                pushEvent(count, EV_ABS, ABS_MT_SLOT, slot);
                if (!wasUploaded)
                    pushEvent(count, EV_ABS, ABS_MT_TRACKING_ID, finger.id);
                
                // 人类化：微小的随机抖动（±1像素）
                int jitterX = static_cast<int>((humanRandFloat() - 0.5f) * 2.0f);
                int jitterY = static_cast<int>((humanRandFloat() - 0.5f) * 2.0f);
                int posX = static_cast<int>(finger.pos.x) + jitterX;
                int posY = static_cast<int>(finger.pos.y) + jitterY;
                
                pushEvent(count, EV_ABS, ABS_MT_POSITION_X, posX);
                pushEvent(count, EV_ABS, ABS_MT_POSITION_Y, posY);
                pushEvent(count, EV_ABS, ABS_X, posX);
                pushEvent(count, EV_ABS, ABS_Y, posY);
                
                // 人类化：模拟压力和接触面积
                int pressure = 128 + humanRand() % 64;  // 128-192之间波动
                int touchMajor = 20 + humanRand() % 15;  // 20-35之间波动
                int touchMinor = touchMajor - 5 - humanRand() % 5;
                
                pushEvent(count, EV_ABS, ABS_MT_PRESSURE, pressure);
                pushEvent(count, EV_ABS, ABS_PRESSURE, pressure);
                pushEvent(count, EV_ABS, ABS_MT_TOUCH_MAJOR, touchMajor);
                pushEvent(count, EV_ABS, ABS_MT_TOUCH_MINOR, touchMinor);
                pushEvent(count, EV_ABS, ABS_MT_WIDTH_MAJOR, touchMajor);
                pushEvent(count, EV_ABS, ABS_MT_TOOL_TYPE, 0);  // MT_TOOL_FINGER
                
                g_uploadedFingerDown[di][fi] = true;
            } else if (wasUploaded) {
                pushEvent(count, EV_ABS, ABS_MT_SLOT, slot);
                pushEvent(count, EV_ABS, ABS_MT_TRACKING_ID, -1);
                g_uploadedFingerDown[di][fi] = false;
            }
        }
    }

    pushEvent(count, EV_KEY, BTN_TOUCH, hasActiveFinger ? 1 : 0);
    pushEvent(count, EV_KEY, BTN_TOOL_FINGER, activeFingerCount == 1 ? 1 : 0);
    pushEvent(count, EV_SYN, SYN_REPORT, 0);
    write(g_outputFd, g_inputBuffer.event, sizeof(input_event) * count);
}

// ─── Zone detection ─────────────────────────────────────────────────

// ─── Device scanning ────────────────────────────────────────────────

static bool checkDeviceIsTouch(int fd) {
    uint8_t* bits = nullptr;
    ssize_t bitsSize = 0;
    int res = 0;
    bool hasSlot = false, hasX = false, hasY = false;
    input_absinfo abs{};
    while (true) {
        res = ioctl(fd, EVIOCGBIT(EV_ABS, bitsSize), bits);
        if (res < bitsSize) break;
        bitsSize = res + 16;
        bits = static_cast<uint8_t*>(realloc(bits, bitsSize * 2));
    }
    for (int j = 0; j < res; ++j) {
        for (int k = 0; k < 8; ++k) {
            int code = j * 8 + k;
            if ((bits[j] & (1 << k)) && ioctl(fd, EVIOCGABS(code), &abs) == 0) {
                if (code == ABS_MT_SLOT) hasSlot = true;
                if (code == ABS_MT_POSITION_X) hasX = true;
                if (code == ABS_MT_POSITION_Y) hasY = true;
            }
        }
    }
    free(bits);
    return hasSlot && hasX && hasY;
}

// ─── uinput device creation ─────────────────────────────────────────

static bool createUinputDevice(int screenX, int screenY, int sourceFd) {
    uinput_user_dev uiDev{};
    g_outputFd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_outputFd <= 0) {
        LOGE("open /dev/uinput failed");
        return false;
    }

    // 从真实触控芯片列表中随机选择，替代原随机字符串（提升隐蔽性）
    static const char* touch_chip_names[] = {
        "goodix_ts", "ft5x06_ts", "atmel_mxt_ts", "synaptics_dsx",
        "cyttsp5_i2c", "ilitek_ts", "novatek_ts", "focaltech_ts",
        "himax_ts", "sitronix_ts"
    };
    static const unsigned short touch_vendor_ids[] = {
        0x27C6, 0x38F7, 0x03EB, 0x06CB,
        0x04B4, 0x222A, 0x0603, 0x38F7,
        0x1241, 0x1016
    };
    static const int chip_count = sizeof(touch_chip_names) / sizeof(touch_chip_names[0]);
    int chip_idx = rand() % chip_count;
    strncpy(uiDev.name, touch_chip_names[chip_idx], UINPUT_MAX_NAME_SIZE - 1);
    uiDev.name[UINPUT_MAX_NAME_SIZE - 1] = '\0';
    
    // 使用真实的触控芯片参数（I2C总线 + 真实厂商ID）
    uiDev.id.bustype = BUS_I2C;
    uiDev.id.vendor = touch_vendor_ids[chip_idx];
    uiDev.id.product = 0x0001 + (rand() & 0x0F);
    uiDev.id.version = 0x0100 + (rand() & 0x0FF);

    ioctl(g_outputFd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_ABS);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_X);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_Y);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_PRESSURE);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_TOUCH_MINOR);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_WIDTH_MAJOR);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_TOOL_TYPE);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_SYN);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_KEY);
    ioctl(g_outputFd, UI_SET_KEYBIT, KEY_F4);
    ioctl(g_outputFd, UI_SET_KEYBIT, KEY_POWER);
    ioctl(g_outputFd, UI_SET_KEYBIT, KEY_SLEEP);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOUCH);

    // 深度伪装：使用类似真实设备的phys路径
    static const char* fake_phys_paths[] = {
        "i2c/0-0038/input/input",
        "i2c/1-005d/input/input",
        "i2c/2-0020/input/input",
        "platform/soc/78b9000.i2c/i2c-3/3-0040/input/input",
        "platform/goodix_ts.0/input/input"
    };
    static const int phys_count = sizeof(fake_phys_paths) / sizeof(fake_phys_paths[0]);
    int phys_idx = rand() % phys_count;
    char physPath[64];
    snprintf(physPath, sizeof(physPath), "%s%d", fake_phys_paths[phys_idx], rand() % 10);
    ioctl(g_outputFd, UI_SET_PHYS, physPath);

    input_id id{};
    if (ioctl(sourceFd, EVIOCGID, &id) == 0) uiDev.id = id;

    uint8_t* bits = nullptr;
    ssize_t bitsSize = 0;
    int res = 0;
    while (true) {
        res = ioctl(sourceFd, EVIOCGBIT(EV_KEY, bitsSize), bits);
        if (res < bitsSize) break;
        bitsSize = res + 16;
        bits = static_cast<uint8_t*>(realloc(bits, bitsSize * 2));
    }
    for (int j = 0; j < res; ++j) {
        for (int k = 0; k < 8; ++k) {
            int code = j * 8 + k;
            if (bits[j] & (1 << k)) {
                if (code == BTN_TOUCH || code == BTN_TOOL_FINGER) continue;
                ioctl(g_outputFd, UI_SET_KEYBIT, code);
            }
        }
    }
    free(bits);

    // 深度伪装：10个触点（和真实设备一致）
    uiDev.absmin[ABS_MT_SLOT] = 0;
    uiDev.absmax[ABS_MT_SLOT] = 9;  // 10个触点
    uiDev.absmin[ABS_MT_POSITION_X] = 0;
    uiDev.absmax[ABS_MT_POSITION_X] = screenX;
    uiDev.absmin[ABS_MT_POSITION_Y] = 0;
    uiDev.absmax[ABS_MT_POSITION_Y] = screenY;
    uiDev.absmin[ABS_X] = 0;
    uiDev.absmax[ABS_X] = screenX;
    uiDev.absmin[ABS_Y] = 0;
    uiDev.absmax[ABS_Y] = screenY;
    uiDev.absmin[ABS_MT_TRACKING_ID] = 0;
    uiDev.absmax[ABS_MT_TRACKING_ID] = 65535;
    // 压力和接触面积（模拟真实触控）
    uiDev.absmin[ABS_PRESSURE] = 0;
    uiDev.absmax[ABS_PRESSURE] = 255;
    uiDev.absmin[ABS_MT_PRESSURE] = 0;
    uiDev.absmax[ABS_MT_PRESSURE] = 255;
    uiDev.absmin[ABS_MT_TOUCH_MAJOR] = 0;
    uiDev.absmax[ABS_MT_TOUCH_MAJOR] = 255;
    uiDev.absmin[ABS_MT_TOUCH_MINOR] = 0;
    uiDev.absmax[ABS_MT_TOUCH_MINOR] = 255;
    uiDev.absmin[ABS_MT_WIDTH_MAJOR] = 0;
    uiDev.absmax[ABS_MT_WIDTH_MAJOR] = 255;
    uiDev.absmin[ABS_MT_TOOL_TYPE] = 0;
    uiDev.absmax[ABS_MT_TOOL_TYPE] = 0;  // MT_TOOL_FINGER
    write(g_outputFd, &uiDev, sizeof(uiDev));

    if (ioctl(g_outputFd, UI_DEV_CREATE)) {
        LOGE("UI_DEV_CREATE failed");
        close(g_outputFd);
        g_outputFd = 0;
        return false;
    }
    return true;
}

// ─── Close ──────────────────────────────────────────────────────────

static void closeTouchLocked() {
    if (!g_initialized) return;
    for (auto& device : g_devices) {
        ioctl(device.fd, EVIOCGRAB, UNGRAB);
        close(device.fd);
        device.fd = 0;
    }
    if (g_outputFd > 0) {
        ioctl(g_outputFd, UI_DEV_DESTROY);
        close(g_outputFd);
        g_outputFd = 0;
    }
    memset(g_inputBuffer.event, 0, sizeof(g_inputBuffer.event));
    g_uploadedFingerDown = {};
    g_initialized = false;
    g_devices.clear();
}

// ─── Reader thread ──────────────────────────────────────────────────

static void* deviceReader(void* arg) {
    int devIdx = static_cast<int>(reinterpret_cast<long>(arg));
    Device& dev = g_devices[devIdx];

    int curSlot = 0;
    input_event batch[64];

    while (g_running) {
        ssize_t n = read(dev.fd, batch, sizeof(batch));
        if (n <= 0 || n % sizeof(input_event) != 0) continue;

        size_t count = n / sizeof(input_event);
        std::lock_guard<std::mutex> guard(g_mutex);

        for (size_t j = 0; j < count; j++) {
            auto& ie = batch[j];

            if (ie.type == EV_ABS) {
                switch (ie.code) {
                case ABS_MT_SLOT:
                    curSlot = ie.value;
                    break;
                case ABS_MT_TRACKING_ID:
                    if (curSlot >= 0 && curSlot < maxF) {
                        if (ie.value == -1)
                            dev.fingers[curSlot].isDown = false;
                        else {
                            dev.fingers[curSlot].isDown = true;
                            dev.fingers[curSlot].id =
                                static_cast<int>((devIdx * 2 + 1) * maxF + curSlot);
                        }
                    }
                    break;
                case ABS_MT_POSITION_X:
                    if (curSlot >= 0 && curSlot < maxF) {
                        dev.fingers[curSlot].pos.x = ie.value * dev.s2tx;
                        dev.fingers[curSlot].isDown = true;
                    }
                    break;
                case ABS_MT_POSITION_Y:
                    if (curSlot >= 0 && curSlot < maxF) {
                        dev.fingers[curSlot].pos.y = ie.value * dev.s2ty;
                        dev.fingers[curSlot].isDown = true;
                    }
                    break;
                }
            }

            if (ie.type == EV_SYN && ie.code == SYN_REPORT) {
                upload();
            }
        }
    }

    LOGD("Reader[%d]: stopped", devIdx);
    return nullptr;
}

// ═════════════════════════════════════════════════════════════════════
//  Public API (touch_core.h)
// ═════════════════════════════════════════════════════════════════════

bool touch_init(int screenW, int screenH) {
    if (screenW <= 0 || screenH <= 0) {
        LOGE("touch_init: invalid screen size %dx%d", screenW, screenH);
        return false;
    }
    std::lock_guard<std::mutex> guard(g_mutex);
    closeTouchLocked();

    Vec2 size(static_cast<float>(screenW), static_cast<float>(screenH));
    g_screenSize = size.x > size.y ? size : Vec2(size.y, size.x);
    g_screen_w = screenW;
    g_screen_h = screenH;

    DIR* dir = opendir("/dev/input/");
    if (!dir) { LOGE("open /dev/input failed"); return false; }

    dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (!strstr(entry->d_name, "event")) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        int fd = open(path, O_RDWR);
        if (fd < 0) continue;
        if (!checkDeviceIsTouch(fd)) { close(fd); continue; }

        Device device{};
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &device.absX) == 0 &&
            ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &device.absY) == 0) {
            device.fd = fd;
            ioctl(fd, EVIOCGRAB, GRAB);
            g_devices.push_back(device);
            LOGD("touch device %s max=%d,%d", path, device.absX.maximum, device.absY.maximum);
        } else {
            close(fd);
        }
    }
    closedir(dir);

    if (g_devices.empty()) { LOGE("no touch device found"); return false; }

    int touchMaxX = g_devices[0].absX.maximum;
    int touchMaxY = g_devices[0].absY.maximum;
    if (!createUinputDevice(touchMaxX, touchMaxY, g_devices[0].fd)) {
        closeTouchLocked();
        return false;
    }

    for (auto& device : g_devices) {
        device.s2tx = static_cast<float>(touchMaxX) / std::max(1, device.absX.maximum);
        device.s2ty = static_cast<float>(touchMaxY) / std::max(1, device.absY.maximum);
    }

    Vec2 logical = size;
    if (logical.x > logical.y) std::swap(logical.x, logical.y);
    g_touchScale.x = static_cast<float>(touchMaxX) / std::max(1.0f, logical.x);
    g_touchScale.y = static_cast<float>(touchMaxY) / std::max(1.0f, logical.y);
    g_initialized = true;
    LOGD("touch ready scale=%.3f,%.3f", g_touchScale.x, g_touchScale.y);
    return true;
}

void touch_close(void) {
    touch_stop_readers();
    std::lock_guard<std::mutex> guard(g_mutex);
    closeTouchLocked();
}

bool touch_is_initialized(void) { return g_initialized; }
int  touch_get_output_fd(void)   { return g_outputFd; }

void touch_start_readers(void) {
    if (g_running) return;
    if (!g_initialized) return;

    g_running = true;
    g_reader_threads.resize(g_devices.size());
    for (size_t i = 0; i < g_devices.size(); i++) {
        if (pthread_create(&g_reader_threads[i], nullptr, deviceReader,
                           reinterpret_cast<void*>(i)) != 0) {
            LOGE("pthread_create failed for device %zu", i);
            g_running = false;
            g_reader_threads.resize(i);
            return;
        }
    }
    LOGD("Started %zu reader threads", g_devices.size());
}

void touch_stop_readers(void) {
    if (!g_running) return;
    g_running = false;
    for (auto& t : g_reader_threads)
        pthread_join(t, nullptr);
    g_reader_threads.clear();
    LOGD("Stopped all readers");
}

void touch_set_screen_params(int w, int h, int rotation) {
    g_screen_w = w;
    g_screen_h = h;
    g_rotation = normalizeRotation(rotation);
}

void touch_down(int slot, int id, int screenX, int screenY) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return;
    float tx, ty;
    screenToTouch(screenX, screenY, tx, ty);
    g_devices[0].fingers[slot].id = id;
    g_devices[0].fingers[slot].pos = Vec2(tx, ty);
    g_devices[0].fingers[slot].isDown = true;
    upload();
}

void touch_move(int slot, int screenX, int screenY) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return;
    float tx, ty;
    screenToTouch(screenX, screenY, tx, ty);
    g_devices[0].fingers[slot].pos = Vec2(tx, ty);
    upload();
}

void touch_up(int slot) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return;
    g_devices[0].fingers[slot].isDown = false;
    upload();
}

void touch_set_trigger_zone(int l, int t, int r, int b)  { g_trigger_zone = {l, t, r, b, 0}; }
void touch_set_ads_zone(int l, int t, int r, int b)      { g_ads_zone = {l, t, r, b, 0}; }
void touch_set_fire_zone(int l, int t, int r, int b)     { g_fire_zone = {l, t, r, b, 0}; }
void touch_set_joystick_zone(int l, int t, int r, int b) { g_joystick_zone = {l, t, r, b, 0}; }

bool touch_is_finger_in_trigger_zone(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return isAnyPhysicalFingerInZoneLocked(g_trigger_zone);
}

bool touch_is_finger_in_ads_zone(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return isAnyPhysicalFingerInZoneLocked(g_ads_zone);
}

bool touch_is_finger_in_fire_zone(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return isAnyPhysicalFingerInZoneLocked(g_fire_zone);
}

bool touch_is_finger_in_joystick_zone(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return isAnyPhysicalFingerInZoneLocked(g_joystick_zone);
}

bool touch_lift_joystick_finger(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return false;

    bool lifted = false;
    int touchMaxX = g_devices[0].absX.maximum;
    int touchMaxY = g_devices[0].absY.maximum;

    for (size_t d = 0; d < g_devices.size(); d++) {
        for (int f = 0; f < maxF; f++) {
            if (!g_devices[d].fingers[f].isDown) continue;
            if (d == 0 && (f == TOUCH_VIRTUAL_SLOT || f == TOUCH_TRIGGER_SLOT)) continue;

            float devX = g_devices[d].fingers[f].pos.x;
            float devY = g_devices[d].fingers[f].pos.y;
            int sx, sy;
            touchToScreen(devX, devY, touchMaxX, touchMaxY, sx, sy);

            if (pointInZone(g_joystick_zone, sx, sy)) {
                g_devices[d].fingers[f].isDown = false;
                lifted = true;
                LOGD("liftJoystickFinger: dev%zu finger%d at (%d,%d)", d, f, sx, sy);
            }
        }
    }

    if (lifted) upload();
    return lifted;
}
