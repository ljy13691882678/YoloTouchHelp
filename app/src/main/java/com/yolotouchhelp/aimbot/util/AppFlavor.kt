package com.yolotouchhelp.aimbot.util

object AppFlavor {
    /** Current build flavor: "infer" or "host" */
    private set var flavor: String = ""
    val isInfer: Boolean get() = flavor == "infer"
    val isHost: Boolean get() = flavor == "host"

    fun setup(packageName: String) {
        flavor = if (packageName.contains(".infer")) "infer" else "host"
    }
}
