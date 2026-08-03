package com.batterycast.quant

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionBindingVerificationTest {
    @Test fun productionDoesNotBindFakeTelemetry(){
        val root=File("src/main")
        val text=root.walkTopDown().filter{it.isFile && it.extension in setOf("kt","java")}.joinToString("\n"){it.readText()}
        assertTrue(text.contains("AndroidBatteryTelemetrySource"))
        assertFalse(Regex("bindTelemetry\\s*\\([^)]*(Fake|Demo|Sample|Mock)",RegexOption.IGNORE_CASE).containsMatchIn(text))
        assertFalse(Regex("class\\s+(Fake|Demo|Sample|Mock).*Telemetry",RegexOption.IGNORE_CASE).containsMatchIn(text))
    }
    @Test fun productionManifestHasNoInternetPermission(){
        val manifest=File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }
}
