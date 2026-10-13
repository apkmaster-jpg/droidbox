package com.droidbox.app

import android.app.Application

class DroidBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The native VM engine library is loaded by QemuEngine on first use.
        // Nothing fake is initialized here.
    }
}
