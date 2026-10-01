package com.adcbtracker

import android.app.Application
import com.adcbtracker.data.AppDb
import com.adcbtracker.data.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var db: AppDb
        private set
    lateinit var repo: TransactionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDb.get(this)
        repo = TransactionRepository(db)
        applicationScope.launch { repo.populateDefaultCategoriesIfNeeded() }
    }
}
