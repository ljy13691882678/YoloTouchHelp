package com.yolotouchhelp.aimbot.util

object AppFlavor {
    private var flavor: String = ""

    /** True when running the infer (NPU) flavor */
    val isInfer: Boolean get() = flavor == "infer"

    /** True when running the host flavor */
    val isHost: Boolean get() = flavor == "host"

    /** Call once at app/service startup to detect the current flavor */
    fun setup(packageName: String) {
        flavor = if (packageName.contains(".infer")) "infer" else "host"
    }
}
