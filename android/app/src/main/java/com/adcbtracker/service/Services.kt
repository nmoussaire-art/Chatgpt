package com.adcbtracker.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.adcbtracker.App
import com.adcbtracker.data.Prefs
import com.adcbtracker.parser.AdcbAlertParser
import com.adcbtracker.parser.ParsedTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private suspend fun store(context: Context, source: String, parsed: ParsedTransaction, tag: String) {
    val app = context.applicationContext as? App ?: return
    try {
        val isNew = app.repo.ingestParsed(source, parsed)
        Prefs.setLastCapturedTx(app, System.currentTimeMillis())
        if (isNew) {
            LargeExpenseNotifier.maybeNotify(app, parsed)
            SpendWidget.refresh(app)
        }
        Log.i(tag, "Transaction stored: ${parsed.amountMinor / 100.0} ${parsed.currency} at ${parsed.merchant}")
    } catch (e: Exception) {
        Log.e(tag, "Failed to store transaction", e)
    }
}

class AdcbNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Prefs.setLastHeartbeat(applicationContext, System.currentTimeMillis())
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, AdcbNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val extras = sbn?.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()
        val combined = listOf(title, bigText, text, subText).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (combined.isBlank() || !AdcbAlertParser.looksLikeAdcbAlert(title, combined)) return
        val parsed = AdcbAlertParser.parse(combined) ?: run {
            Log.w(TAG, "Failed to parse ADCB alert: ${combined.take(200)}")
            return
        }
        scope.launch { store(applicationContext, "notification", parsed, TAG) }
    }

    companion object {
        private const val TAG = "AdcbNotificationListener"
        const val SETTINGS_ACTION = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(context, AdcbNotificationListener::class.java).flattenToString())
        }
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val sender = messages.first().originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (!AdcbAlertParser.looksLikeAdcbAlert(sender, body)) return
        val parsed = AdcbAlertParser.parse(body) ?: return
        val pending = goAsync()
        val app = context.applicationContext as? App ?: return pending.finish()
        app.applicationScope.launch {
            try {
                store(context, "sms", parsed, "SmsReceiver")
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Log.i("BootReceiver", "Boot completed - ADCB Tracker ready")
    }
}
