package com.adcbtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransactionEntity::class, Category::class, MerchantCategoryMapping::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantCategoryMappingDao(): MerchantCategoryMappingDao

    companion object {
        @Volatile private var instance: AppDb? = null

        // Kept from v2.3 so a database created by the very first version still upgrades.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `icon` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `isDefault` INTEGER NOT NULL DEFAULT 0)"
                )
                DefaultCategories.all.forEach { c ->
                    db.execSQL(
                        "INSERT INTO `categories` (`id`, `name`, `icon`, `colorHex`, `isDefault`) VALUES (?, ?, ?, ?, 1)",
                        arrayOf(c.id, c.name, c.icon, c.colorHex),
                    )
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merchant_category_mappings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`merchantOriginal` TEXT NOT NULL, `merchantNormalized` TEXT NOT NULL, `categoryId` INTEGER, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON DELETE SET NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_category_mappings_merchantNormalized` ON `merchant_category_mappings` (`merchantNormalized`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_category_mappings_categoryId` ON `merchant_category_mappings` (`categoryId`)")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `categoryId` INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "adcb_tracker.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
