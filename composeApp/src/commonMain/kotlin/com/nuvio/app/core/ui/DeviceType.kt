package com.nuvio.app.core.ui

/**
 * Form factor of the device the app is running on.
 *
 * The TV build shares the phone UI, so every interactive surface asks for the
 * current device type before it enables D-pad behaviour (focus rings, remote
 * activation, TV specific navigation).
 */
enum class DeviceType {
    TV,
    TABLET,
    PHONE;

    /** True for Android TV / leanback devices and for touch-less boxes. */
    val isTv: Boolean get() = this == TV

    /** True when the device is expected to be driven by touch. */
    val isTouch: Boolean get() = this != TV
}

/** Detects the current form factor. Implemented per platform. */
expect fun detectDeviceType(): DeviceType
