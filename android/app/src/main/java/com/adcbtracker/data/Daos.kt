package com.adcbtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: TransactionEntity): Long

    @Query("SELECT * FROM transactions ORDER BY tsEpochMillis DESC")
    fun all(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY tsEpochMillis DESC")
    suspend fun allOnce(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE tsEpochMillis >= :from ORDER BY tsEpochMillis DESC")
    fun since(from: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE tsEpochMillis >= :start AND tsEpochMillis < :end ORDER BY tsEpochMillis DESC")
    fun inRange(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun byId(id: Long): Flow<TransactionEntity?>

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCategory(id: Long, categoryId: Long?)

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE LOWER(TRIM(merchant)) = LOWER(TRIM(:merchant))")
    suspend fun updateCategoryForMerchant(merchant: String, categoryId: Long?)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        DELETE FROM transactions
        WHERE source != 'manual' AND (
            rawText LIKE '%transferred via%' OR rawText LIKE '%received towards%'
            OR rawText LIKE '%has been credited%' OR rawText LIKE '%credited to your account%'
            OR rawText LIKE '%your salary%'
        )
        """
    )
    suspend fun deleteNonExpenses(): Int

    @Query(
        """
        SELECT t.merchant AS merchant, COUNT(*) AS transactionCount,
            SUM(CASE WHEN t.amountMinor > 0 THEN t.amountMinor ELSE 0 END) AS totalSpent,
            t.categoryId AS categoryId, c.name AS categoryName, c.icon AS categoryIcon
        FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id
        WHERE t.merchant IS NOT NULL AND t.merchant != ''
        GROUP BY LOWER(TRIM(t.merchant))
        ORDER BY transactionCount DESC
        """
    )
    fun merchantsWithCategories(): Flow<List<MerchantWithCategory>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun totalCount(): Flow<Int>
}

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Query("DELETE FROM categories WHERE id = :id AND isDefault = 0")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun all(): Flow<List<Category>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface MerchantCategoryMappingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: MerchantCategoryMapping)

    @Query("DELETE FROM merchant_category_mappings WHERE merchantNormalized = :normalized")
    suspend fun deleteByMerchant(normalized: String)

    @Query("SELECT categoryId FROM merchant_category_mappings WHERE merchantNormalized = :normalized LIMIT 1")
    suspend fun categoryIdFor(normalized: String): Long?
}
