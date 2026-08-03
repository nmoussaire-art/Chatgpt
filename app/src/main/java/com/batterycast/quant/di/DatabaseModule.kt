package com.batterycast.quant.di

import android.content.Context
import androidx.room.Room
import com.batterycast.quant.core.database.BatteryCastDatabase
import com.batterycast.quant.core.database.dao.CapabilityEvidenceDao
import com.batterycast.quant.core.database.dao.ForecastRecordDao
import com.batterycast.quant.core.database.dao.ModelCellDao
import com.batterycast.quant.core.database.dao.NotificationStateDao
import com.batterycast.quant.core.database.dao.ObservationDao
import com.batterycast.quant.core.database.dao.PrecisionSessionDao
import com.batterycast.quant.core.database.dao.RegimeTransitionDao
import com.batterycast.quant.core.database.dao.ResidualDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * The database is created empty and stays local.
     *
     * There is deliberately no prepopulate callback, no asset database, and no seeding of any
     * kind: the first row appears only when the device produces its first real reading.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BatteryCastDatabase =
        Room.databaseBuilder(context, BatteryCastDatabase::class.java, BatteryCastDatabase.NAME)
            .build()

    @Provides fun provideObservationDao(db: BatteryCastDatabase): ObservationDao = db.observationDao()

    @Provides fun provideModelCellDao(db: BatteryCastDatabase): ModelCellDao = db.modelCellDao()

    @Provides fun provideRegimeTransitionDao(db: BatteryCastDatabase): RegimeTransitionDao =
        db.regimeTransitionDao()

    @Provides fun provideResidualDao(db: BatteryCastDatabase): ResidualDao = db.residualDao()

    @Provides fun provideForecastRecordDao(db: BatteryCastDatabase): ForecastRecordDao =
        db.forecastRecordDao()

    @Provides fun provideCapabilityEvidenceDao(db: BatteryCastDatabase): CapabilityEvidenceDao =
        db.capabilityEvidenceDao()

    @Provides fun providePrecisionSessionDao(db: BatteryCastDatabase): PrecisionSessionDao =
        db.precisionSessionDao()

    @Provides fun provideNotificationStateDao(db: BatteryCastDatabase): NotificationStateDao =
        db.notificationStateDao()
}
