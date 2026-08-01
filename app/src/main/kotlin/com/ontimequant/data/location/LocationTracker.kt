package com.ontimequant.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.CurrentLocationRequest
import com.ontimequant.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

enum class LocationAccess { NONE, FOREGROUND, BACKGROUND }

/**
 * Thin, testable wrapper over the Fused Location Provider.
 *
 * The app is designed so that everything here is optional:
 *  - No permission at all → the user picks an origin manually; every screen still works.
 *  - Foreground only → "use my current location" works, and trip completion is confirmed
 *    by the user from a card on the home screen.
 *  - Background → arrival can be detected by geofence without the app being open.
 *
 * There is no continuous location logging in any mode. Positions are requested one at a
 * time, used immediately, and never written to the database.
 */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    fun access(): LocationAccess = when {
        !hasForeground() -> LocationAccess.NONE
        hasBackground() -> LocationAccess.BACKGROUND
        else -> LocationAccess.FOREGROUND
    }

    fun hasForeground(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            hasForeground()
        }

    /** A single fresh fix, or null if unavailable. Never throws. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): GeoPoint? {
        if (!hasForeground()) return null
        return runCatching {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setMaxUpdateAgeMillis(60_000)
                .setDurationMillis(10_000)
                .build()
            client.getCurrentLocation(request, null).await()
                ?.let { GeoPoint(it.latitude, it.longitude) }
                ?: client.lastLocation.await()?.let { GeoPoint(it.latitude, it.longitude) }
        }.getOrNull()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Registers an arrival geofence around the destination while a journey is in flight.
 *
 * Only ever one geofence at a time, and it is removed the moment the trip is recorded, so
 * the app is not holding a standing subscription to the user's movements.
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracker: LocationTracker,
) {

    private val client: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }

    @SuppressLint("MissingPermission")
    suspend fun watchArrival(tripObservationId: String, destination: GeoPoint, radiusMetres: Float = 180f): Boolean {
        if (!tracker.hasBackground()) return false
        return runCatching {
            val fence = Geofence.Builder()
                .setRequestId(tripObservationId)
                .setCircularRegion(destination.latitude, destination.longitude, radiusMetres)
                .setExpirationDuration(4 * 60 * 60 * 1000L)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setLoiteringDelay(60_000)
                .build()
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofence(fence)
                .build()
            client.addGeofences(request, GeofenceBroadcastReceiver.pendingIntent(context)).await()
            true
        }.getOrDefault(false)
    }

    suspend fun stopWatching(tripObservationId: String) {
        runCatching { client.removeGeofences(listOf(tripObservationId)).await() }
    }
}

/**
 * Receives arrival transitions. It does not do the work itself — it enqueues a WorkManager
 * job so the recording survives process death and respects background execution limits.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = com.google.android.gms.location.GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return
        com.ontimequant.work.WorkScheduler.enqueueArrivalDetected(context, ids.first())
    }

    companion object {
        fun pendingIntent(context: Context): android.app.PendingIntent {
            val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            return android.app.PendingIntent.getBroadcast(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
        }
    }
}
