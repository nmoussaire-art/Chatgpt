package com.ontimequant.data.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ontimequant.data.prefs.NavigationApp
import com.ontimequant.model.SavedLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the user's chosen navigation app.
 *
 * Kept behind an interface for two reasons: the Compose UI test can assert that tapping
 * *Navigate* actually fires the launch without a real maps app being installed, and a
 * different navigation target can be added without touching any screen.
 */
interface NavigationLauncher {
    /** Returns true when an app was actually opened. */
    fun launch(destination: SavedLocation, preferred: NavigationApp): Boolean

    /** Which of the preferred apps are actually installed on this device. */
    fun availableApps(): List<NavigationApp>
}

@Singleton
class AndroidNavigationLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : NavigationLauncher {

    override fun launch(destination: SavedLocation, preferred: NavigationApp): Boolean {
        val attempts = buildList {
            when (preferred) {
                NavigationApp.GOOGLE_MAPS -> add(googleMapsIntent(destination))
                NavigationApp.WAZE -> add(wazeIntent(destination))
                NavigationApp.OSMAND -> add(geoIntent(destination).setPackage(NavigationApp.OSMAND.packageName))
                NavigationApp.SYSTEM_DEFAULT -> Unit
            }
            // Always fall back to the generic geo: intent so the user still gets somewhere.
            add(geoIntent(destination))
        }

        for (intent in attempts) {
            val resolvable = intent.resolveActivity(context.packageManager) != null
            if (!resolvable) continue
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        }
        return false
    }

    override fun availableApps(): List<NavigationApp> = NavigationApp.entries.filter { app ->
        val pkg = app.packageName ?: return@filter true
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    private fun googleMapsIntent(destination: SavedLocation) = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(
            "google.navigation:q=${destination.point.latitude},${destination.point.longitude}&mode=d",
        ),
    ).setPackage(NavigationApp.GOOGLE_MAPS.packageName)

    private fun wazeIntent(destination: SavedLocation) = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("waze://?ll=${destination.point.latitude},${destination.point.longitude}&navigate=yes"),
    )

    private fun geoIntent(destination: SavedLocation) = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(
            "geo:${destination.point.latitude},${destination.point.longitude}?q=" +
                Uri.encode("${destination.point.latitude},${destination.point.longitude}(${destination.label})"),
        ),
    )
}
