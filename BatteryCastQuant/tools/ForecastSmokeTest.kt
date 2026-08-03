import com.batterycast.quant.forecast.BatteryForecastEngine
import com.batterycast.quant.forecast.ChargePlanner
import com.batterycast.quant.forecast.DrainEstimator
import com.batterycast.quant.forecast.ObservationCleaner
import com.batterycast.quant.model.*

private fun observation(
    timestamp: Long,
    percent: Double,
    charging: Boolean = false,
    regime: UsageRegime = UsageRegime.NORMAL,
    plug: PlugType = if (charging) PlugType.AC else PlugType.NONE,
    bootCount: Int = 1,
    elapsed: Long = timestamp,
    current: Long? = if (charging) 1_400_000 else -350_000,
    counter: Long? = 2_000_000,
    energy: Long? = null
) = BatteryObservation(
    timestamp = timestamp,
    elapsedRealtimeMs = elapsed,
    bootCount = bootCount,
    batteryPercent = percent,
    chargeCounterMicroAh = counter,
    currentMicroA = current,
    averageCurrentMicroA = current,
    energyNanoWh = energy,
    voltageMv = 3_900,
    temperatureDeciC = 310,
    isCharging = charging,
    plugType = plug,
    batteryHealth = 2,
    chargingStatus = null,
    chargingPolicy = null,
    powerSaveEnabled = false,
    thermalStatus = 0,
    screenInteractive = !charging,
    networkType = NetworkType.WIFI,
    bluetoothEnabled = false,
    recentScreenTimeMs = 30_000,
    recentForegroundUsageMs = 30_000,
    usageRegime = if (charging) UsageRegime.CHARGING else regime,
    overallQuality = DataQuality.HIGH,
    source = "tool-fixture"
)

private fun dischargeHistory(
    start: Long = 1_000_000_000L,
    count: Int = 40,
    intervalMinutes: Int = 15,
    startPercent: Double = 90.0,
    dropPerPoint: Double = 0.55,
    regime: UsageRegime = UsageRegime.NORMAL
) = (0 until count).map { index ->
    observation(
        timestamp = start + index * intervalMinutes * 60_000L,
        percent = startPercent - index * dropPerPoint,
        regime = regime,
        counter = (2_700_000L - index * 18_000L).coerceAtLeast(100_000L),
        energy = (12_000_000_000L - index * 70_000_000L).coerceAtLeast(100_000_000L)
    )
}

fun main() {
    val history = dischargeHistory()
    val now = history.last().timestamp
    val target = now + 6 * 60 * 60_000L
    val engine = BatteryForecastEngine(simulations = 2_000, randomSeed = 20260803L)
    val baseline = engine.forecast(history, target, 10, now = now)
    check(baseline.survivalProbability != null && baseline.survivalProbability in 0.0..1.0)
    check(baseline.points.all {
        it.p05 <= it.p10 && it.p10 <= it.p25 && it.p25 <= it.median &&
            it.median <= it.p75 && it.p75 <= it.p90 && it.p90 <= it.p95
    })
    check(baseline.points.all { it.p05 in 0.0..100.0 && it.p95 in 0.0..100.0 })
    check(baseline.targetP05!! <= baseline.targetP10!!)
    check(baseline.targetP10 <= baseline.targetP25!!)
    check(baseline.targetP25 <= baseline.medianAtTarget!!)
    check(baseline.medianAtTarget <= baseline.targetP75!!)
    check(baseline.targetP75 <= baseline.targetP90!!)
    check(baseline.targetP90 <= baseline.targetP95!!)

    val navigation = engine.forecast(
        history,
        target,
        10,
        ScenarioDefinition("navigation", "Navigation", UsageRegime.NAVIGATION, 180),
        now
    )
    check(navigation.medianAtTarget!! <= baseline.medianAtTarget)

    val mixed = engine.forecast(
        history,
        target,
        10,
        ScenarioDefinition(
            id = "mixed",
            title = "Custom mixed usage",
            regime = UsageRegime.NORMAL,
            durationMinutes = 120,
            mix = mapOf(
                UsageRegime.STANDBY to .25,
                UsageRegime.NORMAL to .50,
                UsageRegime.MEDIA_GAMING to .25
            )
        ),
        now
    )
    check(mixed.survivalProbability != null)
    check(mixed.mainDriver.contains("custom", ignoreCase = true) || mixed.mainDriver.contains("confidence", ignoreCase = true))

    val estimate = checkNotNull(DrainEstimator().estimate(history, now))
    check(estimate.microAhPerHour != null && estimate.microAhPerHour > 0.0)
    check(estimate.milliWhPerHour != null && estimate.milliWhPerHour > 0.0)

    val rebootBoundary = listOf(
        observation(now, 70.0, bootCount = 1, elapsed = 900_000L),
        observation(now + 15 * 60_000L, 69.0, bootCount = 1, elapsed = 1_800_000L),
        observation(now + 30 * 60_000L, 50.0, bootCount = 2, elapsed = 20_000L),
        observation(now + 45 * 60_000L, 49.5, bootCount = 2, elapsed = 920_000L),
        observation(now + 60 * 60_000L, 49.0, bootCount = 2, elapsed = 1_820_000L)
    )
    val rebootEstimate = DrainEstimator().estimate(ObservationCleaner().clean(rebootBoundary).valid, rebootBoundary.last().timestamp)
    check(rebootEstimate != null && rebootEstimate.percentPerHour < 20.0)

    val chargingStart = now + 10 * 60_000L
    val charging = (0..30).map { index ->
        val pct = 25.0 + if (index < 22) index * 1.7 else 22 * 1.7 + (index - 22) * 0.7
        observation(
            timestamp = chargingStart + index * 5 * 60_000L,
            percent = pct.coerceAtMost(96.0),
            charging = true,
            plug = PlugType.AC
        )
    }
    val live = observation(charging.last().timestamp + 10 * 60_000L, 38.0)
    val planHistory = history + charging + live
    val plan = ChargePlanner(simulations = 2_000, randomSeed = 20260803L).plan(
        planHistory,
        ChargePlanRequest(live.timestamp + 5 * 60 * 60_000L, 55, 0.80),
        live.timestamp
    )
    check(plan.latestSafeStart != null)
    check(plan.recommendedDurationMinutes != null && plan.recommendedDurationMinutes > 0)
    check(plan.confidenceAchieved != null && plan.confidenceAchieved in 0.0..1.0)

    val single = observation(now, 54.0, current = null, counter = null, energy = null)
    val coldStart = engine.forecast(listOf(single), now + 60 * 60_000L, 10, now = now)
    check(coldStart.survivalProbability == null)
    check(coldStart.preliminary)

    println("BatteryCast Quant forecasting smoke test: PASS")
    println("Baseline median at target: %.2f%%".format(baseline.medianAtTarget))
    println("Navigation median at target: %.2f%%".format(navigation.medianAtTarget))
    println("Safe charge duration: ${plan.recommendedDurationMinutes} minutes")
}
