package com.batterycast.quant.core.database

import com.batterycast.quant.core.database.dao.CapabilityEvidenceDao
import com.batterycast.quant.core.database.entity.CapabilityEvidenceEntity
import com.batterycast.quant.telemetry.CapabilityStore
import com.batterycast.quant.telemetry.validation.CapabilityEvidence
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed persistence of learned sensor behaviour.
 *
 * On a fresh install this returns a zeroed evidence record, which maps to
 * `FieldSupport.UNKNOWN` everywhere — the app reports "still working out what this device
 * supports" rather than claiming capabilities it has not verified.
 */
@Singleton
class RoomCapabilityStore @Inject constructor(
    private val dao: CapabilityEvidenceDao,
) : CapabilityStore {

    override suspend fun evidence(): CapabilityEvidence =
        dao.get()?.toDomain() ?: CapabilityEvidence()

    override suspend fun update(evidence: CapabilityEvidence) {
        dao.upsert(evidence.toEntity())
    }
}

internal fun CapabilityEvidenceEntity.toDomain(): CapabilityEvidence = CapabilityEvidence(
    samples = samples,
    currentNowPresent = currentNowPresent,
    currentNowAbsent = currentNowAbsent,
    currentAveragePresent = currentAveragePresent,
    currentAverageAbsent = currentAverageAbsent,
    chargeCounterPresent = chargeCounterPresent,
    chargeCounterAbsent = chargeCounterAbsent,
    energyPresent = energyPresent,
    energyAbsent = energyAbsent,
    voltagePresent = voltagePresent,
    voltageAbsent = voltageAbsent,
    temperaturePresent = temperaturePresent,
    temperatureAbsent = temperatureAbsent,
    thermalPresent = thermalPresent,
    thermalAbsent = thermalAbsent,
    chargingPolicyPresent = chargingPolicyPresent,
    chargingPolicyAbsent = chargingPolicyAbsent,
    currentMicroampLike = currentMicroampLike,
    currentMilliampLike = currentMilliampLike,
    currentPositiveWhileCharging = currentPositiveWhileCharging,
    currentNegativeWhileCharging = currentNegativeWhileCharging,
    currentPositiveWhileDischarging = currentPositiveWhileDischarging,
    currentNegativeWhileDischarging = currentNegativeWhileDischarging,
    smallestPercentStep = smallestPercentStep,
    designCapacityMilliAh = designCapacityMilliAh,
)

internal fun CapabilityEvidence.toEntity(): CapabilityEvidenceEntity = CapabilityEvidenceEntity(
    samples = samples,
    currentNowPresent = currentNowPresent,
    currentNowAbsent = currentNowAbsent,
    currentAveragePresent = currentAveragePresent,
    currentAverageAbsent = currentAverageAbsent,
    chargeCounterPresent = chargeCounterPresent,
    chargeCounterAbsent = chargeCounterAbsent,
    energyPresent = energyPresent,
    energyAbsent = energyAbsent,
    voltagePresent = voltagePresent,
    voltageAbsent = voltageAbsent,
    temperaturePresent = temperaturePresent,
    temperatureAbsent = temperatureAbsent,
    thermalPresent = thermalPresent,
    thermalAbsent = thermalAbsent,
    chargingPolicyPresent = chargingPolicyPresent,
    chargingPolicyAbsent = chargingPolicyAbsent,
    currentMicroampLike = currentMicroampLike,
    currentMilliampLike = currentMilliampLike,
    currentPositiveWhileCharging = currentPositiveWhileCharging,
    currentNegativeWhileCharging = currentNegativeWhileCharging,
    currentPositiveWhileDischarging = currentPositiveWhileDischarging,
    currentNegativeWhileDischarging = currentNegativeWhileDischarging,
    smallestPercentStep = smallestPercentStep,
    designCapacityMilliAh = designCapacityMilliAh,
)
