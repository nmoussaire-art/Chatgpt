package com.adcbtracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["tsEpochMillis"]),
        Index(value = ["categoryId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val cardId: String?,
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val location: String?,
    val tsEpochMillis: Long,
    val avlCreditLimitMinor: Long?,
    val rawText: String,
    val dedupeKey: String,
    val categoryId: Long? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val colorHex: String,
    val isDefault: Boolean = false,
)

@Entity(
    tableName = "merchant_category_mappings",
    indices = [
        Index(value = ["merchantNormalized"], unique = true),
        Index(value = ["categoryId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class MerchantCategoryMapping(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantOriginal: String,
    val merchantNormalized: String,
    val categoryId: Long?,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

data class MerchantWithCategory(
    val merchant: String,
    val transactionCount: Int,
    val totalSpent: Long,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryIcon: String?,
)

/** Raw row joined with its category, for display. */
data class TransactionWithCategory(
    val tx: TransactionEntity,
    val category: Category?,
)

object DefaultCategories {
    val all = listOf(
        Category(1, "Food & Dining", "🍽️", "#FF5722", true),
        Category(2, "Transportation", "🚗", "#2196F3", true),
        Category(3, "Shopping", "🛍️", "#9C27B0", true),
        Category(4, "Entertainment", "🎬", "#E91E63", true),
        Category(5, "Groceries", "🛒", "#4CAF50", true),
        Category(6, "Health & Medical", "💊", "#00BCD4", true),
        Category(7, "Utilities & Bills", "💡", "#FFC107", true),
        Category(8, "Travel", "✈️", "#3F51B5", true),
        Category(9, "Subscriptions", "📱", "#607D8B", true),
        Category(10, "Personal Care", "💇", "#FF9800", true),
        Category(11, "Education", "📚", "#795548", true),
        Category(12, "Other", "📦", "#9E9E9E", true),
    )
}
