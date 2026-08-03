package com.batterycast.quant.di

import android.content.Context
import androidx.room.Room
import com.batterycast.quant.data.BatteryDao
import com.batterycast.quant.data.BatteryDatabase
import com.batterycast.quant.data.ForecastDao
import com.batterycast.quant.forecast.AccuracyCalculator
import com.batterycast.quant.forecast.BatteryForecastEngine
import com.batterycast.quant.forecast.ChargePlanner
import com.batterycast.quant.telemetry.AndroidBatteryTelemetrySource
import com.batterycast.quant.telemetry.BatteryTelemetrySource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryBindings {
    @Binds
    @Singleton
    abstract fun bindTelemetry(impl: AndroidBatteryTelemetrySource): BatteryTelemetrySource
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BatteryDatabase =
        Room.databaseBuilder(context, BatteryDatabase::class.java, "batterycast.db").build()

    @Provides
    fun batteryDao(database: BatteryDatabase): BatteryDao = database.batteryDao()

    @Provides
    fun forecastDao(database: BatteryDatabase): ForecastDao = database.forecastDao()

    @Provides
    @Singleton
    fun forecastEngine(): BatteryForecastEngine = BatteryForecastEngine(simulations = 2_000)

    @Provides
    @Singleton
    fun chargePlanner(): ChargePlanner = ChargePlanner(simulations = 2_000)

    @Provides
    @Singleton
    fun accuracyCalculator(): AccuracyCalculator = AccuracyCalculator()
}
