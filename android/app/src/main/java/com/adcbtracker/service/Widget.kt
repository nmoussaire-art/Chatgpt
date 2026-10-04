package com.adcbtracker.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.adcbtracker.App
import com.adcbtracker.MainActivity
import com.adcbtracker.R
import com.adcbtracker.data.Cycles
import com.adcbtracker.data.Prefs
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.data.spendMinor
import com.adcbtracker.data.toUaeDate
import com.adcbtracker.parser.ParsedTransaction
import com.adcbtracker.ui.components.formatMoney
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Home-screen widget: today's spend, cycle total and (if set) what's safe to spend per day. */
class SpendWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                SpendWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

object SpendWidget {
    suspend fun refresh(context: Context) {
        val app = context.applicationContext as? App ?: return
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val today = LocalDate.now(UAE_ZONE)
        val cycle = Cycles.cycleFor(today, Prefs.getCycleStartDay(context))
        val txs = app.repo.since(cycle.startMillis).first()
        val todayTotal = txs.filter { it.tx.tsEpochMillis.toUaeDate() == today }.sumOf { it.tx.spendMinor }
        val cycleTotal = txs.sumOf { it.tx.spendMinor }
        val budget = Prefs.getCycleBudgetMinor(context)
        val daysLeft = cycle.lengthDays - cycle.dayIndex(today)
        val footer = when {
            budget <= 0 -> "Day ${cycle.dayIndex(today) + 1} of ${cycle.lengthDays} · ${daysLeft - 1} days to statement"
            cycleTotal > budget -> "Over budget by ${formatMoney(cycleTotal - budget)}"
            else -> "Safe to spend ${formatMoney((budget - cycleTotal) / daysLeft)}/day"
        }

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_spend).apply {
            setTextViewText(R.id.widget_today, formatMoney(todayTotal))
            setTextViewText(R.id.widget_cycle, "Cycle ${cycle.label(includeYear = false)}: ${formatMoney(cycleTotal)}")
            setTextViewText(R.id.widget_footer, footer)
            setOnClickPendingIntent(R.id.widget_root, open)
        }
        manager.updateAppWidget(ids, views)
    }
}

object LargeExpenseNotifier {
    private const val CHANNEL_ID = "large_expense"

    fun maybeNotify(context: Context, p: ParsedTransaction) {
        val threshold = Prefs.getLargeExpenseThresholdMinor(context)
        if (!Prefs.getLargeExpenseNotify(context) || p.amountMinor < threshold) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Large expenses", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle("Large expense: ${formatMoney(p.amountMinor, p.currency)}")
            .setContentText(listOfNotNull(p.merchant, p.location).joinToString(" · ").ifBlank { "Card ${p.cardId ?: ""}" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(p.raw.hashCode(), n)
    }
}
