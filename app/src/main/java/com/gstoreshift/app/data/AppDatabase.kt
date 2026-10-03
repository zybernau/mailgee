package com.gstoreshift.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        AuxAccountEntity::class,
        UploadReceiptEntity::class,
        ExclusionEntryEntity::class,
        MigrationBatchEntity::class,
    ],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItems(): MediaItemDao
    abstract fun auxAccounts(): AuxAccountDao
    abstract fun receipts(): ReceiptDao
    abstract fun exclusions(): ExclusionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun build(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "gstoreshift.db")
                    .build().also { instance = it }
            }
    }
}
