package com.foulfoxhacks.mellozone

import android.app.Application
import com.foulfoxhacks.mellozone.core.Core

class MelloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}
