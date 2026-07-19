// gyro_core.h — Real gyroscope device injection API
//
// 扫描 /dev/input/eventX，定位真实的陀螺仪设备（暴露 ABS_RX/ABS_RY/ABS_RZ
// 旋转轴、且不是触摸屏的设备），并以 root 权限向其写入 EV_ABS 事件，
// 让读取该设备的应用（游戏内置陀螺仪瞄准）感知到注入的旋转。
//
// 仅依赖 Linux input 接口，不依赖 Android Sensor HAL。
// 与 touch_core 共存：触摸通道继续走 uinput，陀螺仪通道走真实设备。

#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 初始化：扫描 /dev/input 找到陀螺仪设备并打开写句柄。
// 成功返回 true；未找到设备返回 false（调用方应回退到触摸方案）。
bool gyro_init(void);

// 关闭陀螺仪设备并释放资源。
void gyro_close(void);

// 是否已成功初始化。
bool gyro_is_initialized(void);

// 设置像素到陀螺仪原始值的灵敏度倍率（默认 1.0）。
// 越大，相同像素位移产生的旋转速率越大。
void gyro_set_sensitivity(float sensitivity);

// 注入一次陀螺仪位移。
//   dx > 0 : 准星向右移动（对应 yaw，写入 ABS_RY）
//   dy > 0 : 准星向下移动（对应 pitch，写入 ABS_RX，符号取反）
// 内部自动做像素 → 原始轴值的缩放，并对设备 ABS 范围做 clamp。
// 返回是否成功写入。
bool gyro_inject_move(float dx, float dy);

#ifdef __cplusplus
}
#endif
