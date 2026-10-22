package com.nuvio.app.core.ui

import android.content.Context

/**
 * Holds the application context so platform helpers (form factor detection,
 * audio capability probing) can be used from anywhere without a Context
 * parameter.
 */
internal object PlatformAppContext {
    @Volatile
    var value: Context? = null
        private set

    fun install(context: Context) {
        value = context.applicationContext
    }
}
