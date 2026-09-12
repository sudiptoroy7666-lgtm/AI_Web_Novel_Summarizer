package com.example.novel_summary

import android.app.Application
import com.example.novel_summary.data.AppDatabase

class App : Application() {

    companion object {
        lateinit var database: AppDatabase
        var appContext: Application? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        database = AppDatabase.getDatabase(this)
    }
}