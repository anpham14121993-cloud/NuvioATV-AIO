package com.nuvio.app.core.ui

import android.content.Context
import android.content.pm.PackageManager

/** Called once from the application object so the form factor can be detected. */
internal fun initDeviceTypeContext(context: Context) {
    PlatformAppContext.install(context)
}

actual fun detectDeviceType(): DeviceType {
    val context = PlatformAppContext.value ?: return DeviceType.PHONE
    val packageManager = context.packageManager
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    ) {
        return DeviceType.TV
    }
    // Boxes and projectors without a touchscreen still expect a remote.
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
        return DeviceType.TV
    }
    val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
    return if (smallestWidthDp >= 600) DeviceType.TABLET else DeviceType.PHONE
}
