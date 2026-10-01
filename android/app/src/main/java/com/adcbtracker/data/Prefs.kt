package com.adcbtracker.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val DEFAULT_LARGE_EXPENSE_THRESHOLD_MINOR = 100_000L
    const val DEFAULT_CYCLE_START_DAY = 24

    private const val FILE_NAME = "adcb_tracker_prefs"
    private const val KEY_LARGE_EXPENSE_THRESHOLD_MINOR = "large_expense_threshold_minor"
    private const val KEY_LAST_CAPTURED_TX_EPOCH_MILLIS = "last_captured_tx_epoch_millis"
    private const val KEY_LAST_HEARTBEAT_EPOCH_MILLIS = "last_heartbeat_epoch_millis"
    private const val KEY_CYCLE_START_DAY = "cycle_start_day"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getLargeExpenseThresholdMinor(context: Context): Long =
        prefs(context).getLong(KEY_LARGE_EXPENSE_THRESHOLD_MINOR, DEFAULT_LARGE_EXPENSE_THRESHOLD_MINOR)

    fun setLargeExpenseThresholdMinor(context: Context, minor: Long) =
        prefs(context).edit().putLong(KEY_LARGE_EXPENSE_THRESHOLD_MINOR, minor).apply()

    fun getLastHeartbeat(context: Context): Long = prefs(context).getLong(KEY_LAST_HEARTBEAT_EPOCH_MILLIS, 0L)

    fun setLastHeartbeat(context: Context, epochMillis: Long) =
        prefs(context).edit().putLong(KEY_LAST_HEARTBEAT_EPOCH_MILLIS, epochMillis).apply()

    fun getLastCapturedTx(context: Context): Long = prefs(context).getLong(KEY_LAST_CAPTURED_TX_EPOCH_MILLIS, 0L)

    fun setLastCapturedTx(context: Context, epochMillis: Long) =
        prefs(context).edit().putLong(KEY_LAST_CAPTURED_TX_EPOCH_MILLIS, epochMillis).apply()

    /** Day of month (1-28) on which the card's billing cycle starts. */
    fun getCycleStartDay(context: Context): Int =
        prefs(context).getInt(KEY_CYCLE_START_DAY, DEFAULT_CYCLE_START_DAY).coerceIn(1, 28)

    fun setCycleStartDay(context: Context, day: Int) =
        prefs(context).edit().putInt(KEY_CYCLE_START_DAY, day.coerceIn(1, 28)).apply()
}
