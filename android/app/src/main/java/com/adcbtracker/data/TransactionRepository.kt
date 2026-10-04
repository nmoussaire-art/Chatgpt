package com.adcbtracker.data

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.adcbtracker.parser.AdcbAlertParser
import com.adcbtracker.parser.ParsedTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.Locale

class TransactionRepository(private val db: AppDb) {
    private val transactionDao = db.transactionDao()
    private val categoryDao = db.categoryDao()
    private val mappingDao = db.merchantCategoryMappingDao()

    val categories: Flow<List<Category>> = categoryDao.all()

    private fun withCategories(source: Flow<List<TransactionEntity>>): Flow<List<TransactionWithCategory>> =
        combine(source, categories) { txs, cats ->
            val byId = cats.associateBy { it.id }
            txs.map { TransactionWithCategory(it, it.categoryId?.let(byId::get)) }
        }

    fun all(): Flow<List<TransactionWithCategory>> = withCategories(transactionDao.all())

    fun since(fromMillis: Long): Flow<List<TransactionWithCategory>> = withCategories(transactionDao.since(fromMillis))

    fun inRange(start: Long, end: Long): Flow<List<TransactionWithCategory>> =
        withCategories(transactionDao.inRange(start, end))

    fun totalCount(): Flow<Int> = transactionDao.totalCount()

    fun byId(id: Long): Flow<TransactionWithCategory?> =
        combine(transactionDao.byId(id), categories) { tx, cats ->
            tx?.let { TransactionWithCategory(it, it.categoryId?.let { c -> cats.firstOrNull { cat -> cat.id == c } }) }
        }

    fun merchantsWithCategories(): Flow<List<MerchantWithCategory>> = transactionDao.merchantsWithCategories()

    /** Returns true if the transaction was new. */
    suspend fun ingestParsed(source: String, p: ParsedTransaction): Boolean {
        val epochMillis = p.timestamp.atZone(UAE_ZONE).toInstant().toEpochMilli()
        val merchantNorm = normalizeMerchant(p.merchant)
        val dedupeKey = "${epochMillis}_${p.amountMinor}_$merchantNorm"
        val categoryId = if (merchantNorm.isNotEmpty()) mappingDao.categoryIdFor(merchantNorm) else null
        val entity = TransactionEntity(
            source = source,
            cardId = p.cardId,
            amountMinor = p.amountMinor,
            currency = p.currency,
            merchant = p.merchant,
            location = p.location,
            tsEpochMillis = epochMillis,
            avlCreditLimitMinor = p.avlCreditLimitMinor,
            rawText = p.raw,
            dedupeKey = dedupeKey,
            categoryId = categoryId,
        )
        return transactionDao.insertIgnore(entity) != -1L
    }

    suspend fun addManualTransaction(amountMinor: Long, currency: String, merchant: String, categoryId: Long?, epochMillis: Long) {
        transactionDao.insertIgnore(
            TransactionEntity(
                source = "manual",
                cardId = null,
                amountMinor = amountMinor,
                currency = currency,
                merchant = merchant.trim().ifBlank { null },
                location = null,
                tsEpochMillis = epochMillis,
                avlCreditLimitMinor = null,
                rawText = "Manual entry",
                dedupeKey = "manual_${System.currentTimeMillis()}_${amountMinor}",
                categoryId = categoryId,
            )
        )
    }

    suspend fun deleteTransaction(id: Long) = transactionDao.delete(id)

    suspend fun deleteNonExpenseTransactions(): Int = transactionDao.deleteNonExpenses()

    suspend fun updateTransactionCategory(id: Long, categoryId: Long?) = transactionDao.updateCategory(id, categoryId)

    /** Remembers the category for this merchant and applies it to all its past transactions. */
    suspend fun mapMerchantToCategory(merchant: String, categoryId: Long?) {
        val norm = normalizeMerchant(merchant)
        if (categoryId == null) {
            mappingDao.deleteByMerchant(norm)
        } else {
            mappingDao.upsert(MerchantCategoryMapping(merchantOriginal = merchant, merchantNormalized = norm, categoryId = categoryId))
        }
        transactionDao.updateCategoryForMerchant(merchant, categoryId)
    }

    suspend fun addCategory(name: String, icon: String, colorHex: String): Long =
        categoryDao.insert(Category(name = name, icon = icon, colorHex = colorHex))

    suspend fun updateCategory(category: Category) = categoryDao.update(category)

    suspend fun deleteCategory(id: Long) = categoryDao.delete(id)

    suspend fun populateDefaultCategoriesIfNeeded() {
        if (categoryDao.count() == 0) categoryDao.insertAll(DefaultCategories.all)
    }

    suspend fun getAllForExport(): List<TransactionEntity> = transactionDao.allOnce()

    data class ImportResult(val scanned: Int, val matched: Int, val imported: Int)

    /** Scans the SMS inbox for ADCB alerts and stores any that are not already tracked. */
    suspend fun importFromSmsInbox(context: Context): ImportResult = withContext(Dispatchers.IO) {
        var scanned = 0
        var matched = 0
        var imported = 0
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body"), null, null, "date DESC"
            )?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val bodyIdx = c.getColumnIndex("body")
                while (c.moveToNext()) {
                    scanned++
                    val address = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else ""
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx).orEmpty() else ""
                    if (body.isBlank() || !AdcbAlertParser.looksLikeAdcbAlert(address, body)) continue
                    val parsed = AdcbAlertParser.parse(body) ?: continue
                    matched++
                    if (ingestParsed("sms", parsed)) imported++
                }
            }
        } catch (e: Exception) {
            Log.e("SmsImporter", "SMS inbox scan failed", e)
        }
        ImportResult(scanned, matched, imported)
    }

    companion object {
        fun normalizeMerchant(merchant: String?): String =
            (merchant ?: "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }
}
