package com.synergiz.mrvc.pmis

import android.app.Application
import com.google.android.material.color.DynamicColors

class MrvcPmisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
