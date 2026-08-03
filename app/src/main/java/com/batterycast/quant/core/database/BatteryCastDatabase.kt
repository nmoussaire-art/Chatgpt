package com.batterycast.quant.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.batterycast.quant.core.database.dao.CapabilityEvidenceDao
import com.batterycast.quant.core.database.dao.ForecastRecordDao
import com.batterycast.quant.core.database.dao.ModelCellDao
import com.batterycast.quant.core.database.dao.NotificationStateDao
import com.batterycast.quant.core.database.dao.ObservationDao
import com.batterycast.quant.core.database.dao.PrecisionSessionDao
import com.batterycast.quant.core.database.dao.RegimeTransitionDao
import com.batterycast.quant.core.database.dao.ResidualDao
import com.batterycast.quant.core.database.entity.CapabilityEvidenceEntity
import com.batterycast.quant.core.database.entity.ForecastRecordEntity
import com.batterycast.quant.core.database.entity.ModelCellEntity
import com.batterycast.quant.core.database.entity.NotificationStateEntity
import com.batterycast.quant.core.database.entity.ObservationEntity
import com.batterycast.quant.core.database.entity.PrecisionSessionEntity
import com.batterycast.quant.core.database.entity.RegimeTransitionEntity
import com.batterycast.quant.core.database.entity.ResidualEntity

/**
 * The complete on-device store.
 *
 * Nothing in this database ever leaves the phone. There is no sync, no upload, no remote mirror,
 * and the app holds no network permission with which to create one. The database ships empty:
 * every row is written from a measurement taken on the device it is running on.
 */
@Database(
    entities = [
        ObservationEntity::class,
        ModelCellEntity::class,
        RegimeTransitionEntity::class,
        ResidualEntity::class,
        ForecastRecordEntity::class,
        CapabilityEvidenceEntity::class,
        PrecisionSessionEntity::class,
        NotificationStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BatteryCastDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao
    abstract fun modelCellDao(): ModelCellDao
    abstract fun regimeTransitionDao(): RegimeTransitionDao
    abstract fun residualDao(): ResidualDao
    abstract fun forecastRecordDao(): ForecastRecordDao
    abstract fun capabilityEvidenceDao(): CapabilityEvidenceDao
    abstract fun precisionSessionDao(): PrecisionSessionDao
    abstract fun notificationStateDao(): NotificationStateDao

    companion object {
        const val NAME = "batterycast.db"
    }
}
