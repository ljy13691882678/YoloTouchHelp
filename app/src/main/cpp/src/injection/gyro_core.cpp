// gyro_core.cpp — Real gyroscope device injection (DIRECT WRITE)
//
// 实现思路：
//   1. 扫描 /dev/input/eventX，定位真实的陀螺仪设备（暴露 ABS_RX/ABS_RY/ABS_RZ
//      旋转轴、且不是触摸屏的设备）。
//   2. 用 root 权限以 O_RDWR 打开，查询各轴 ABS 范围与 fuzz/flat/resolution。
//   3. 维护一对原子变量 g_rate_x / g_rate_y 作为"当前期望角速度"，
//      后台 writer 线程以固定周期（默认 2ms = 500Hz）持续向真实陀螺仪
//      设备节点写入 EV_ABS + EV_SYN 事件。
//   4. gyro_inject_move(dx, dy) 只更新目标角速度，不直接写入。
//      这样即使 AimController 调用频率较低（如每帧 8~16ms），后台线程
//      仍能以高频率持续注入，匹配 Sensor HAL 的采样率，让真机陀螺仪
//      的输出被持续"修改"成我们期望的旋转速率。
//
// 关键点：
//   - 陀螺仪是速率型传感器，静止时输出 0，运动时输出角速度值。
//     因此写入值相对 0，而非相对 (min+max)/2。
//   - 后台 writer 持续注入是必须的：Sensor HAL 以高频率读取设备，
//     单次 write 的事件会被快速覆盖或丢弃。
//   - 设备被 Sensor HAL 持续读取时，我们的写入会进入事件流，
//     被 HAL 转化为 SensorEvent 上报到上层（游戏陀螺仪瞄准）。

#include "gyro_core.h"

#include <cstdio>
#include <ctime>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
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

// 当前注入的目标角速度（像素/帧 → 速率值）
// 后台 writer 线程读取这两个变量持续写入设备
static std::atomic<float> g_rate_x{0.0f};   // pitch（屏幕垂直）
static std::atomic<float> g_rate_y{0.0f};   // yaw（屏幕水平）

// writer 线程控制
static pthread_t g_writer_thread;
static volatile bool g_writer_running = false;

// 注入周期（纳秒）。2ms = 500Hz，匹配多数手机陀螺仪采样率
static constexpr long kInjectPeriodNs = 2 * 1000 * 1000L;

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

// 像素增量 → 陀螺仪角速度值（相对 0，符合速率传感器语义）
// 陀螺仪静止时输出 0，运动时输出 [-half, +half] 范围内的速率值
static int scaleToRate(float delta, int minVal, int maxVal) {
    if (minVal >= maxVal) return 0;
    // 取 |min| 与 |max| 的较大者作为半量程，确保无论范围是否对称都能正确表达速率
    int halfNeg = -minVal;
    int halfPos = maxVal;
    int half = halfNeg > halfPos ? halfNeg : halfPos;
    if (half <= 0) return 0;

    float sens = g_gyro_sensitivity.load(std::memory_order_relaxed);
    float normalized = delta / kPixelFullScale * sens;
    if (normalized > 1.0f) normalized = 1.0f;
    if (normalized < -1.0f) normalized = -1.0f;

    int value = static_cast<int>(normalized * half);
    if (value < minVal) value = minVal;
    if (value > maxVal) value = maxVal;
    return value;
}

// ─── 后台 writer 线程：持续向真机陀螺仪写入速率事件 ──────────────────

static void* gyroWriterThread(void* /*arg*/) {
    // 提升线程优先级（接近 SENSOR 级别）
#ifdef ANDROID
    int prio = -16;  // SCHED_OTHER 高优先级（接近系统 sensor 线程）
    setpriority(PRIO_PROCESS, 0, prio);
#endif

    struct timespec nextWake;
    clock_gettime(CLOCK_MONOTONIC, &nextWake);

    input_event ev[4];

    while (g_writer_running) {
        if (g_gyro_fd < 0) break;

        float rateX = g_rate_x.load(std::memory_order_relaxed);
        float rateY = g_rate_y.load(std::memory_order_relaxed);

        int count = 0;
        // Yaw（绕 Y 轴）→ 屏幕水平位移
        if (g_has_ry) {
            ev[count].type  = EV_ABS;
            ev[count].code  = ABS_RY;
            ev[count].value = scaleToRate(rateY, g_ry_min, g_ry_max);
            count++;
        }
        // Pitch（绕 X 轴）→ 屏幕垂直位移，符号取反
        if (g_has_rx) {
            ev[count].type  = EV_ABS;
            ev[count].code  = ABS_RX;
            ev[count].value = scaleToRate(-rateX, g_rx_min, g_rx_max);
            count++;
        }
        // Roll（绕 Z 轴）通常不用于瞄准，置 0
        if (g_has_rz) {
            ev[count].type  = EV_ABS;
            ev[count].code  = ABS_RZ;
            ev[count].value = 0;
            count++;
        }

        if (count > 0) {
            ev[count].type  = EV_SYN;
            ev[count].code  = SYN_REPORT;
            ev[count].value = 0;
            count++;

            ssize_t written = write(g_gyro_fd, ev, sizeof(input_event) * count);
            if (written < 0 && errno != EAGAIN) {
                // 设备被关闭或错误：停止 writer
                LOGE("gyro writer: write failed errno=%d", errno);
                break;
            }
        }

        // 精确睡眠到下一个周期
        nextWake.tv_nsec += kInjectPeriodNs;
        if (nextWake.tv_nsec >= 1000000000L) {
            nextWake.tv_sec += 1;
            nextWake.tv_nsec -= 1000000000L;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &nextWake, nullptr);
    }

    LOGD("gyro writer thread exited");
    return nullptr;
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

    // 收集所有候选陀螺仪设备的诊断信息
    int candidateCount = 0;

    while ((entry = readdir(dir)) != nullptr) {
        if (!strstr(entry->d_name, "event")) continue;
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);

        // 用 O_RDWR 打开：部分内核要求读写权限才能写入事件
        int fd = open(path, O_RDWR);
        if (fd < 0) continue;

        if (!checkDeviceIsGyro(fd)) {
            close(fd);
            continue;
        }

        candidateCount++;
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
            name[0] = '\0';
        }

        input_absinfo info{};
        bool has_rx_local = false, has_ry_local = false, has_rz_local = false;
        int rx_min = 0, rx_max = 0, ry_min = 0, ry_max = 0, rz_min = 0, rz_max = 0;

        if (ioctl(fd, EVIOCGABS(ABS_RX), &info) == 0) {
            has_rx_local = true;
            rx_min = info.minimum; rx_max = info.maximum;
        }
        if (ioctl(fd, EVIOCGABS(ABS_RY), &info) == 0) {
            has_ry_local = true;
            ry_min = info.minimum; ry_max = info.maximum;
        }
        if (ioctl(fd, EVIOCGABS(ABS_RZ), &info) == 0) {
            has_rz_local = true;
            rz_min = info.minimum; rz_max = info.maximum;
        }

        LOGD("gyro candidate %d: %s name='%s' rx=[%d,%d] ry=[%d,%d] rz=[%d,%d]",
             candidateCount, path, name,
             rx_min, rx_max, ry_min, ry_max, rz_min, rz_max);

        if (!has_rx_local && !has_ry_local && !has_rz_local) {
            close(fd);
            continue;
        }

        // 选中此设备
        g_gyro_fd = fd;
        g_has_rx = has_rx_local;
        g_has_ry = has_ry_local;
        g_has_rz = has_rz_local;
        g_rx_min = rx_min; g_rx_max = rx_max;
        g_ry_min = ry_min; g_ry_max = ry_max;
        g_rz_min = rz_min; g_rz_max = rz_max;

        g_rate_x.store(0.0f, std::memory_order_relaxed);
        g_rate_y.store(0.0f, std::memory_order_relaxed);

        g_gyro_initialized = true;

        // 启动后台 writer 线程
        g_writer_running = true;
        if (pthread_create(&g_writer_thread, nullptr, gyroWriterThread, nullptr) != 0) {
            LOGE("gyro_init: pthread_create failed");
            close(g_gyro_fd);
            g_gyro_fd = -1;
            g_gyro_initialized = false;
            g_has_rx = g_has_ry = g_has_rz = false;
            closedir(dir);
            return false;
        }

        LOGD("gyro_init: opened %s name='%s' rx=[%d,%d] ry=[%d,%d] rz=[%d,%d], writer@500Hz started",
             path, name,
             g_rx_min, g_rx_max, g_ry_min, g_ry_max, g_rz_min, g_rz_max);
        closedir(dir);
        return true;
    }
    closedir(dir);

    LOGE("gyro_init: no gyro device found in /dev/input (scanned all eventX)");
    return false;
}

void gyro_close(void) {
    // 先停 writer 线程（不持锁，避免与 writer 的 write 死锁）
    g_writer_running = false;
    pthread_t t = g_writer_thread;
    if (t != 0) {
        pthread_join(t, nullptr);
        g_writer_thread = 0;
    }

    std::lock_guard<std::mutex> guard(g_gyro_mutex);
    // writer 退出前清零速率，避免残留
    g_rate_x.store(0.0f, std::memory_order_relaxed);
    g_rate_y.store(0.0f, std::memory_order_relaxed);

    if (g_gyro_fd >= 0) {
        // 写一次零速率让设备回归静止
        if (g_has_rx || g_has_ry || g_has_rz) {
            input_event ev[4];
            int count = 0;
            if (g_has_rx) { ev[count].type = EV_ABS; ev[count].code = ABS_RX; ev[count].value = 0; count++; }
            if (g_has_ry) { ev[count].type = EV_ABS; ev[count].code = ABS_RY; ev[count].value = 0; count++; }
            if (g_has_rz) { ev[count].type = EV_ABS; ev[count].code = ABS_RZ; ev[count].value = 0; count++; }
            ev[count].type = EV_SYN; ev[count].code = SYN_REPORT; ev[count].value = 0; count++;
            write(g_gyro_fd, ev, sizeof(input_event) * count);
        }
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

bool gyro_inject_move(float dx, float dy) {
    if (!g_gyro_initialized) return false;
    // 只更新目标角速度，由后台 writer 线程持续写入真机陀螺仪设备
    // 这样即使 AimController 每帧调用一次（~8ms），陀螺仪仍能以 500Hz 持续注入
    g_rate_y.store(dx, std::memory_order_relaxed);   // yaw
    g_rate_x.store(dy, std::memory_order_relaxed);   // pitch
    return true;
}
