package com.loopguard.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Loop::class, LoopEvent::class],
    version = 1,
    exportSchema = true,
)
abstract class LoopDatabase : RoomDatabase() {

    abstract fun loopDao(): LoopDao

    companion object {
        private const val NAME = "loopguard.db"

        @Volatile
        private var instance: LoopDatabase? = null

        fun get(context: Context): LoopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LoopDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
