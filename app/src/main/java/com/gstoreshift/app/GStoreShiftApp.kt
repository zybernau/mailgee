package com.gstoreshift.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.gstoreshift.app.data.AppDatabase

class GStoreShiftApp : Application() {

    lateinit var db: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                MIGRATION_CHANNEL,
                "Migration",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val MIGRATION_CHANNEL = "migration"
    }
}
