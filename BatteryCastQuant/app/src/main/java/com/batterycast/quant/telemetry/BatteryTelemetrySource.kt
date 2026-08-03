package com.batterycast.quant.telemetry

import com.batterycast.quant.model.BatteryObservation

interface BatteryTelemetrySource { suspend fun read(source:String):BatteryObservation? }
