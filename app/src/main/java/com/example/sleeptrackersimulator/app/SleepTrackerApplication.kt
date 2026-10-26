package com.example.sleeptrackersimulator.app

import android.app.Application
import com.example.sleeptrackersimulator.di.AppContainer
import com.example.sleeptrackersimulator.di.DefaultAppContainer

class SleepTrackerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
