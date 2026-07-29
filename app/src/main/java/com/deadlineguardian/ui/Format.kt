package com.deadlineguardian.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.ui.graphics.vector.ImageVector
import com.deadlineguardian.data.DeadlineKind
import com.deadlineguardian.data.ItemKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

fun LocalDate.pretty(): String = format(dateFormat)

/**
 * Minor units back to a readable amount. Currency codes are appended rather than
 * localised into symbols because a receipt in MAD shown as "$" would be actively wrong.
 */
fun formatMoney(minor: Long, currency: String?): String {
    val whole = minor / 100
    val cents = (minor % 100).toInt()
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    val amount = if (cents == 0) grouped else "$grouped.${cents.toString().padStart(2, '0')}"
    return if (currency.isNullOrBlank()) amount else "$amount $currency"
}

fun ItemKind.icon(): ImageVector = when (this) {
    ItemKind.RECEIPT -> Icons.AutoMirrored.Filled.ReceiptLong
    ItemKind.WARRANTY -> Icons.Filled.VerifiedUser
    ItemKind.MEDICINE -> Icons.Filled.LocalPharmacy
    ItemKind.FOOD -> Icons.Filled.Restaurant
    ItemKind.DOCUMENT -> Icons.Filled.Description
    ItemKind.SUBSCRIPTION -> Icons.Filled.Autorenew
    ItemKind.OTHER -> Icons.Filled.Inventory2
}

fun ItemKind.displayName(): String = when (this) {
    ItemKind.RECEIPT -> "Receipt"
    ItemKind.WARRANTY -> "Warranty"
    ItemKind.MEDICINE -> "Medicine"
    ItemKind.FOOD -> "Food"
    ItemKind.DOCUMENT -> "Document"
    ItemKind.SUBSCRIPTION -> "Subscription"
    ItemKind.OTHER -> "Other"
}

fun DeadlineKind.displayName(): String = label
