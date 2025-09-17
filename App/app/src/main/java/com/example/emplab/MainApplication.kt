package com.example.emplab

import Agent.ActivityTracker
import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ActivityTracker.register(this)
    }
}