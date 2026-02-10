package com.example.novel_summary


import android.app.Application
import com.example.novel_summary.data.AppDatabase

class App : Application() {

    companion object {
        lateinit var database: AppDatabase
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
    }
}