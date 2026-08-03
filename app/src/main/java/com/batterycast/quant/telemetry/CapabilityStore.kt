package com.batterycast.quant.telemetry

import com.batterycast.quant.telemetry.validation.CapabilityEvidence

/**
 * Persistence for what we have learned about this device's battery sensors.
 *
 * Kept as an interface so the telemetry layer does not depend on Room; the production binding is
 * the Room-backed implementation in the database package.
 */
interface CapabilityStore {
    suspend fun evidence(): CapabilityEvidence
    suspend fun update(evidence: CapabilityEvidence)
}
