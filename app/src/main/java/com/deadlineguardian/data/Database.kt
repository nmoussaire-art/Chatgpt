package com.deadlineguardian.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

class Converters {
    @TypeConverter fun dateToEpoch(d: LocalDate?): Long? = d?.toEpochDay()
    @TypeConverter fun epochToDate(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)
    @TypeConverter fun itemKindToName(k: ItemKind): String = k.name
    @TypeConverter fun nameToItemKind(s: String): ItemKind =
        runCatching { ItemKind.valueOf(s) }.getOrDefault(ItemKind.OTHER)
    @TypeConverter fun deadlineKindToName(k: DeadlineKind): String = k.name
    @TypeConverter fun nameToDeadlineKind(s: String): DeadlineKind =
        runCatching { DeadlineKind.valueOf(s) }.getOrDefault(DeadlineKind.CUSTOM)
}

@Database(
    entities = [TrackedItem::class, Deadline::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): GuardianDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "deadline-guardian.db"
            ).build().also { instance = it }
        }
    }
}
