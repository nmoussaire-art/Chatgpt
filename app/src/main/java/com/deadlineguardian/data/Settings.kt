package com.deadlineguardian.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deadlineguardian.engine.ScanAnalyzer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("guardian-settings")

data class GuardianSettings(
    val defaultReturnDays: Int = 30,
    val electronicsWarrantyDays: Int = 730,
    val documentLeadDays: Int = 90,
    val preferDayFirst: Boolean = true,
    val notifyHour: Int = 9
) {
    fun toPolicy() = ScanAnalyzer.Policy(
        defaultReturnDays = defaultReturnDays,
        electronicsWarrantyDays = electronicsWarrantyDays,
        preferDayFirst = preferDayFirst,
        documentLeadDays = documentLeadDays
    )
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val returnDays = intPreferencesKey("return_days")
        val warrantyDays = intPreferencesKey("warranty_days")
        val docLead = intPreferencesKey("doc_lead")
        val dayFirst = booleanPreferencesKey("day_first")
        val notifyHour = intPreferencesKey("notify_hour")
    }

    val settings: Flow<GuardianSettings> = context.dataStore.data.map { p ->
        GuardianSettings(
            defaultReturnDays = p[Keys.returnDays] ?: 30,
            electronicsWarrantyDays = p[Keys.warrantyDays] ?: 730,
            documentLeadDays = p[Keys.docLead] ?: 90,
            preferDayFirst = p[Keys.dayFirst] ?: true,
            notifyHour = p[Keys.notifyHour] ?: 9
        )
    }

    suspend fun update(s: GuardianSettings) {
        context.dataStore.edit { p ->
            p[Keys.returnDays] = s.defaultReturnDays
            p[Keys.warrantyDays] = s.electronicsWarrantyDays
            p[Keys.docLead] = s.documentLeadDays
            p[Keys.dayFirst] = s.preferDayFirst
            p[Keys.notifyHour] = s.notifyHour
        }
    }
}
