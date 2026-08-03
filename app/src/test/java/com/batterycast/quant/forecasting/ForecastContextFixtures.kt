package com.batterycast.quant.forecasting

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.fixtures.SnapshotFixtures
import com.batterycast.quant.forecasting.clean.ObservationCleaner
import com.batterycast.quant.forecasting.drain.DrainRateEstimator
import com.batterycast.quant.forecasting.model.DataMaturity
import com.batterycast.quant.forecasting.model.ForecastModelSnapshot
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.regime.RegimeThresholds
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.SensorCapabilities
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Builds a ready-to-simulate [ForecastContext] without touching a database.
 *
 * Test source only. The planner and scenario engines are pure functions of a context plus a
 * snapshot, so this is enough to exercise them exactly as they run in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ForecastContextFixtures {

    /**
     * A [ForecastEngine] whose data-access collaborators are unused.
     *
     * `simulate` and `buildForecast` read only from the context they are handed, so the mocked
     * DAOs are never called; the engine is exercised as production code, not replaced by a stub.
     */
    fun engine(): ForecastEngine = ForecastEngine(
        observationRepository = mockk(relaxed = true),
        modelCellDao = mockk(relaxed = true),
        regimeTransitionDao = mockk(relaxed = true),
        residualDao = mockk(relaxed = true),
        defaultDispatcher = UnconfinedTestDispatcher(),
    )

    fun context(
        nowMs: Long = ObservationFixtures.BASE_TIMESTAMP_MS + 3 * 3_600_000L,
        latest: BatteryObservation? = null,
        history: List<BatteryObservation> = ObservationFixtures.steadyDischarge(
            startPercent = 60.0,
            ratePerHour = 8.0,
            intervalMinutes = 15.0,
            count = 13,
        ),
        snapshot: ForecastModelSnapshot = SnapshotFixtures.snapshot(),
        regime: UsageRegime = UsageRegime.INTERACTIVE,
        maturity: DataMaturity = DataMaturity.PERSONALISED,
        capabilities: SensorCapabilities = SensorCapabilities(),
    ): ForecastContext {
        val cleaned = ObservationCleaner.clean(history)
        val resolvedLatest = latest ?: history.last()
        return ForecastContext(
            latest = resolvedLatest,
            cleaned = cleaned,
            capabilities = capabilities,
            estimates = DrainRateEstimator.estimate(cleaned.segments, capabilities, nowMs),
            regimeThresholds = RegimeThresholds.EMPTY,
            currentRegime = regime,
            snapshot = snapshot,
            maturity = maturity,
            nowMs = nowMs,
        )
    }
}
