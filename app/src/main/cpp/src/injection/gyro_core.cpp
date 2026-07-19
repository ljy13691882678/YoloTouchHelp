// gyro_core.cpp — Real gyroscope device injection
//
// 实现思路：
//   1. 扫描 /dev/input/eventX，找暴露 ABS_RX/ABS_RY/ABS_RZ 旋转轴、
//      且不包含 ABS_MT_SLOT（触摸屏）的设备 → 视为陀螺仪设备。
//   2. 用 root 权限以 O_WRONLY 打开，查询各轴 ABS 范围。
//   3. gyro_inject_move(dx, dy) 把屏幕像素增量按灵敏度映射到原始轴值，
//      生成 EV_ABS + SYN_REPORT 事件批次写入设备节点。
//
// 注意：
//   - 真实陀螺仪设备同时被 Sensor HAL 读取，写入的值会进入事件流。
//   - 单次注入是瞬时值，AimController 每帧调用 moveTo 即可形成持续旋转。
//   - 设备未找到时返回 false，调用方应回退到触摸方案。

#include "gyro_core.h"

#include <cstdio>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <atomic>
#include <cstring>
#include <mutex>

#define LOG_TAG "GyroCore"

#ifdef ANDROID
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) do { fprintf(stderr, "D/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#define LOGE(...) do { fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#endif

// ─── 全局状态 ─────────────────────────────────────────────────────────

static int g_gyro_fd = -1;
static std::mutex g_gyro_mutex;
static bool g_gyro_initialized = false;

static bool g_has_rx = false;
static bool g_has_ry = false;
static bool g_has_rz = false;
static int g_rx_min = 0, g_rx_max = 0;
static int g_ry_min = 0, g_ry_max = 0;
static int g_rz_min = 0, g_rz_max = 0;

// 像素 → 原始轴值的灵敏度倍率
static std::atomic<float> g_gyro_sensitivity{1.0f};

// 假设 100 像素位移对应陀螺仪满量程（经验值，可被 SET_GYRO_SENSITIVITY 调整）
static constexpr float kPixelFullScale = 100.0f;

// ─── 辅助：检测设备是否为陀螺仪 ─────────────────────────────────────

static bool checkDeviceIsGyro(int fd) {
    unsigned char abs_bits[(ABS_MAX + 7) / 8] = {};
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) return false;

    auto hasBit = [&](int code) -> bool {
        return (abs_bits[code / 8] & (1 << (code % 8))) != 0;
    };

    bool has_rx = hasBit(ABS_RX);
    bool has_ry = hasBit(ABS_RY);
    bool has_rz = hasBit(ABS_RZ);
    bool has_mt_slot = hasBit(ABS_MT_SLOT);
    bool has_mt_x = hasBit(ABS_MT_POSITION_X);

    // 必须至少有一个旋转轴，且不是触摸屏
    return (has_rx || has_ry || has_rz) && !has_mt_slot && !has_mt_x;
}

// ─── Public API ──────────────────────────────────────────────────────

bool gyro_init(void) {
    std::lock_guard<std::mutex> guard(g_gyro_mutex);
    if (g_gyro_initialized) return true;

    DIR* dir = opendir("/dev/input/");
    if (!dir) {
        LOGE("gyro_init: cannot open /dev/input");
        return false;
    }

    struct dirent* entry;
    char path[128];
    char name[256] = {};

    while ((entry = readdir(dir)) != nullptr) {
        if (!strstr(entry->d_name, "event")) continue;
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);

        // 先尝试 O_WRONLY，失败再试 O_RDWR（部分内核要求读写权限）
        int fd = open(path, O_WRONLY);
        if (fd < 0) {
            fd = open(path, O_RDWR);
            if (fd < 0) continue;
        }

        if (!checkDeviceIsGyro(fd)) {
            close(fd);
            continue;
        }

        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
            name[0] = '\0';
        }

        input_absinfo info{};
        if (ioctl(fd, EVIOCGABS(ABS_RX), &info) == 0) {
            g_has_rx = true;
            g_rx_min = info.minimum;
            g_rx_max = info.maximum;
        }
        if (ioctl(fd, EVIOCGABS(ABS_RY), &info) == 0) {
            g_has_ry = true;
            g_ry_min = info.minimum;
            g_ry_max = info.maximum;
        }
        if (ioctl(fd, EVIOCGABS(ABS_RZ), &info) == 0) {
            g_has_rz = true;
            g_rz_min = info.minimum;
            g_rz_max = info.maximum;
        }

        if (!g_has_rx && !g_has_ry && !g_has_rz) {
            // 不会发生（checkDeviceIsGyro 已过滤），但保险起见
            close(fd);
            continue;
        }

        g_gyro_fd = fd;
        g_gyro_initialized = true;
        LOGD("gyro_init: opened %s name='%s' rx=[%d,%d] ry=[%d,%d] rz=[%d,%d]",
             path, name,
             g_rx_min, g_rx_max, g_ry_min, g_ry_max, g_rz_min, g_rz_max);
        closedir(dir);
        return true;
    }
    closedir(dir);

    LOGE("gyro_init: no gyro device found in /dev/input");
    return false;
}

void gyro_close(void) {
    std::lock_guard<std::mutex> guard(g_gyro_mutex);
    if (g_gyro_fd >= 0) {
        close(g_gyro_fd);
        g_gyro_fd = -1;
    }
    g_gyro_initialized = false;
    g_has_rx = g_has_ry = g_has_rz = false;
}

bool gyro_is_initialized(void) {
    return g_gyro_initialized;
}

void gyro_set_sensitivity(float sensitivity) {
    if (sensitivity > 0.001f) {
        g_gyro_sensitivity.store(sensitivity, std::memory_order_relaxed);
    }
}

// 像素增量 → 原始轴值，并对设备 ABS 范围做 clamp
static int scaleToAxis(float delta, int minVal, int maxVal) {
    if (minVal >= maxVal) return 0;
    int range = maxVal - minVal;
    int half = range / 2;
    int center = minVal + half;

    float sens = g_gyro_sensitivity.load(std::memory_order_relaxed);
    // 把 delta 像素映射到 [-half, +half]
    float normalized = delta / kPixelFullScale * sens;
    if (normalized > 1.0f) normalized = 1.0f;
    if (normalized < -1.0f) normalized = -1.0f;

    int value = center + static_cast<int>(normalized * half);
    if (value < minVal) value = minVal;
    if (value > maxVal) value = maxVal;
    return value;
}

bool gyro_inject_move(float dx, float dy) {
    std::lock_guard<std::mutex> guard(g_gyro_mutex);
    if (!g_gyro_initialized || g_gyro_fd < 0) return false;

    input_event ev[4];
    int count = 0;

    // Yaw（绕 Y 轴）→ 屏幕水平位移 dx
    if (g_has_ry && dx != 0.0f) {
        ev[count].type  = EV_ABS;
        ev[count].code  = ABS_RY;
        ev[count].value = scaleToAxis(dx, g_ry_min, g_ry_max);
        count++;
    }
    // Pitch（绕 X 轴）→ 屏幕垂直位移 dy，符号取反（前倾 = 视角下移）
    if (g_has_rx && dy != 0.0f) {
        ev[count].type  = EV_ABS;
        ev[count].code  = ABS_RX;
        ev[count].value = scaleToAxis(-dy, g_rx_min, g_rx_max);
        count++;
    }

    if (count == 0) return true;  // 无位移，无需注入

    ev[count].type  = EV_SYN;
    ev[count].code  = SYN_REPORT;
    ev[count].value = 0;
    count++;

    ssize_t written = write(g_gyro_fd, ev, sizeof(input_event) * count);
    if (written != static_cast<ssize_t>(sizeof(input_event) * count)) {
        LOGE("gyro_inject_move: write failed %zd/%zu (errno=%d)",
             written, sizeof(input_event) * count, errno);
        return false;
    }
    return true;
}
