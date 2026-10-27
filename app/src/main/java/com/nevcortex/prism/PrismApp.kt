package com.nevcortex.prism

import android.app.Application
import com.nevcortex.prism.core.util.Logger
import com.nevcortex.prism.di.AppContainer

class PrismApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Logger.i(TAG, "P.R.I.S.M. initialized")
    }

    companion object { private const val TAG = "PrismApp" }
}
