package com.xunlei.ai.injector

interface InjectorCallback {
    fun onConnected()
    fun onDisconnected()
    fun onError(msg: String)
}

interface TouchInjectorInterface {
    fun connect(callback: InjectorCallback)
    fun isConnected(): Boolean
    fun disconnect()

    fun tap(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
    fun moveTo(x: Int, y: Int)
    fun lift()
    fun keepAlive()

    fun triggerDown(x: Int, y: Int)
    fun triggerUp()
    fun triggerTap(x: Int, y: Int, durationMs: Int)

    fun setTriggerZone(left: Int, top: Int, right: Int, bottom: Int)
    fun isFingerInTriggerZone(): Boolean
    fun setAdsZone(left: Int, top: Int, right: Int, bottom: Int)
    fun isFingerInAdsZone(): Boolean
    fun setFireZone(left: Int, top: Int, right: Int, bottom: Int)
    fun isFingerInFireZone(): Boolean
    fun setJoystickZone(left: Int, top: Int, right: Int, bottom: Int)
    fun isFingerInJoystickZone(): Boolean
    fun liftJoystickFinger(): Boolean

    fun setInputMethod(method: Int)
    fun initRemote(): Boolean
    fun setResolution(screenW: Int, screenH: Int, devW: Int, devH: Int)
    fun setOrientationConfig(rotation: Int)

    fun startGeteventListener()
    fun stopGeteventListener()

    fun blockPhysicalTouch()
    fun unblockPhysicalTouch()

    fun destroyRemote()

    fun queryDeviceAbs(devicePath: String, axis: Int): IntArray
    fun findTouchDevice(): String?
}

