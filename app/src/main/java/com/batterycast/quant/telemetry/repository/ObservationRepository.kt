package com.batterycast.quant.telemetry.repository

import com.batterycast.quant.core.database.dao.ObservationDao
import com.batterycast.quant.core.database.entity.toDomain
import com.batterycast.quant.core.database.entity.toEntity
import com.batterycast.quant.di.IoDispatcher
import com.batterycast.quant.telemetry.BatteryTelemetrySource
import com.batterycast.quant.telemetry.LastObservationCache
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.SensorCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the observation history: takes readings, stores them, and serves them to the model.
 *
 * This is the only place that writes observations, which keeps the "no invented data" rule
 * enforceable in one spot: an observation reaches storage only if [BatteryTelemetrySource]
 * produced it from a live platform reading.
 */
@Singleton
class ObservationRepository @Inject constructor(
    private val telemetrySource: BatteryTelemetrySource,
    private val observationDao: ObservationDao,
    private val lastObservationCache: LastObservationCache,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Serialises sampling so two triggers firing together cannot store a torn pair. */
    private val sampleMutex = Mutex()

    /**
     * Takes a live reading and stores it.
     *
     * Returns null when the platform had nothing to give — immediately after boot, for example.
     * It never stores a placeholder in that case.
     */
    suspend fun recordSample(source: ObservationSource): BatteryObservation? = withContext(ioDispatcher) {
        sampleMutex.withLock {
            primeCacheFromStorage()

            val observation = telemetrySource.sample(source) ?: return@withLock null
            val previous = lastObservationCache.get()

            // Two samples arriving within the debounce window describe the same instant. Keep the
            // first, unless the newer one carries a state change worth recording.
            if (previous != null && shouldSuppress(previous, observation, source)) {
                return@withLock previous
            }

            val enriched = observation.copy(
                recentScreenTimeMs = estimateRecentScreenTimeMs(observation),
            )

            val id = observationDao.insert(enriched.toEntity())
            val stored = if (id > 0) enriched.copy(id = id) else enriched
            lastObservationCache.set(stored)
            stored
        }
    }

    /**
     * Duplicate suppression.
     *
     * Battery broadcasts can arrive in bursts — level, plug and health each fire one — and the
     * app also samples on open and on a schedule. Storing every one of them would bloat the
     * database without adding information, so near-identical readings taken seconds apart are
     * collapsed. A state change is always kept, because state changes are exactly what the model
     * needs to segment on.
     */
    private fun shouldSuppress(
        previous: BatteryObservation,
        candidate: BatteryObservation,
        source: ObservationSource,
    ): Boolean {
        if (source == ObservationSource.PRECISION_SESSION) return false

        val stateChanged = previous.isCharging != candidate.isCharging ||
            previous.plugType != candidate.plugType ||
            previous.powerSaveEnabled != candidate.powerSaveEnabled ||
            previous.screenInteractive != candidate.screenInteractive ||
            previous.bootSessionId != candidate.bootSessionId
        if (stateChanged) return false

        val percentChanged = candidate.batteryPercent != previous.batteryPercent
        if (percentChanged) return false

        val gapMs = candidate.timestampMs - previous.timestampMs
        return gapMs in 0 until DUPLICATE_WINDOW_MS
    }

    /**
     * Reconstructs how long the screen was interactive over the recent window.
     *
     * Derived from stored observations only: each consecutive pair contributes its gap if the
     * earlier reading had the screen on. That undercounts screen time between sparse samples, so
     * the figure is treated as a lower bound and never used to claim precise screen-on minutes.
     * Returns null when there is not enough history to say anything.
     */
    private suspend fun estimateRecentScreenTimeMs(current: BatteryObservation): Long? {
        val windowStart = current.timestampMs - RECENT_SCREEN_WINDOW_MS
        val history = observationDao.between(windowStart, current.timestampMs).map { it.toDomain() }
        if (history.size < 2) return null

        var screenOnMs = 0L
        for (index in 0 until history.size - 1) {
            val earlier = history[index]
            val later = history[index + 1]
            val gap = later.elapsedRealtimeMs - earlier.elapsedRealtimeMs
            // A non-positive gap means the device rebooted between the two; nothing can be
            // attributed across that boundary.
            if (gap <= 0) continue
            // A gap longer than the sampling interval tells us nothing about the middle of it, so
            // only the part we can vouch for is counted.
            val attributable = min(gap, MAX_ATTRIBUTABLE_GAP_MS)
            if (earlier.screenInteractive) screenOnMs += attributable
        }

        val last = history.last()
        val tailGap = current.elapsedRealtimeMs - last.elapsedRealtimeMs
        if (tailGap > 0 && last.screenInteractive) {
            screenOnMs += min(tailGap, MAX_ATTRIBUTABLE_GAP_MS)
        }

        return max(0L, min(screenOnMs, RECENT_SCREEN_WINDOW_MS))
    }

    /** Restores the in-memory continuity anchor after a cold start, reboot, or force-stop. */
    suspend fun primeCacheFromStorage() {
        if (lastObservationCache.get() != null) return
        lastObservationCache.primeIfEmpty(observationDao.latest()?.toDomain())
    }

    suspend fun latest(): BatteryObservation? = withContext(ioDispatcher) {
        observationDao.latest()?.toDomain()
    }

    fun latestFlow(): Flow<BatteryObservation?> = observationDao.latestFlow().map { it?.toDomain() }

    suspend fun since(sinceMs: Long): List<BatteryObservation> = withContext(ioDispatcher) {
        observationDao.since(sinceMs).map { it.toDomain() }
    }

    fun sinceFlow(sinceMs: Long): Flow<List<BatteryObservation>> =
        observationDao.sinceFlow(sinceMs).map { list -> list.map { it.toDomain() } }

    suspend fun between(fromMs: Long, toMs: Long): List<BatteryObservation> = withContext(ioDispatcher) {
        observationDao.between(fromMs, toMs).map { it.toDomain() }
    }

    suspend fun nearest(targetMs: Long, toleranceMs: Long): BatteryObservation? = withContext(ioDispatcher) {
        observationDao.nearest(targetMs, toleranceMs)?.toDomain()
    }

    suspend fun count(): Int = withContext(ioDispatcher) { observationDao.count() }

    fun countFlow(): Flow<Int> = observationDao.countFlow()

    suspend fun earliestTimestamp(): Long? = withContext(ioDispatcher) { observationDao.earliestTimestamp() }

    suspend fun capabilities(): SensorCapabilities = telemetrySource.capabilities()

    /** Live battery state without touching storage; used for the instant-refresh path. */
    fun liveObservations(): Flow<BatteryObservation> = telemetrySource.observations()

    /** Drops history older than the retention window. Called from maintenance work. */
    suspend fun prune(nowMs: Long, retentionMs: Long = DEFAULT_RETENTION_MS): Int = withContext(ioDispatcher) {
        observationDao.deleteBefore(nowMs - retentionMs)
    }

    suspend fun deleteAll() = withContext(ioDispatcher) {
        observationDao.deleteAll()
        lastObservationCache.clear()
    }

    companion object {
        /** Readings closer together than this, with nothing changed, add no information. */
        const val DUPLICATE_WINDOW_MS = 45_000L

        /** Window over which recent screen time is reconstructed. */
        const val RECENT_SCREEN_WINDOW_MS = 60 * 60 * 1000L

        /** Longest gap that a single reading is allowed to speak for. */
        const val MAX_ATTRIBUTABLE_GAP_MS = 20 * 60 * 1000L

        /** Four weeks is enough for day-of-week personalisation without unbounded growth. */
        const val DEFAULT_RETENTION_MS = 28L * 24 * 60 * 60 * 1000
    }
}
