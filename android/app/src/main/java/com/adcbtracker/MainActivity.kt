package com.adcbtracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.ui.CategoriesScreen
import com.adcbtracker.ui.HomeScreen
import com.adcbtracker.ui.InsightsScreen
import com.adcbtracker.ui.MainViewModel
import com.adcbtracker.ui.SettingsScreen
import com.adcbtracker.ui.TransactionsScreen
import com.adcbtracker.ui.components.CategorySheet
import com.adcbtracker.ui.components.DayDetailSheet
import com.adcbtracker.ui.components.TransactionSheet
import com.adcbtracker.ui.theme.AdcbTheme
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink0
import com.adcbtracker.ui.theme.TextMid
import java.time.LocalDate

private enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    Insights("insights", "Insights", Icons.Default.Insights),
    Transactions("transactions", "Expenses", Icons.AutoMirrored.Filled.ReceiptLong),
    Categories("categories", "Categories", Icons.Default.Category),
    Settings("settings", "Settings", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.Factory(application as App) }

    private val smsPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Live SMS capture needs these; ask once on first launch.
        if (savedInstanceState == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED
        ) {
            val perms = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            smsPermissions.launch(perms.toTypedArray())
        }

        setContent {
            AdcbTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
                val daySummary by vm.selectedDaySummary.collectAsStateWithLifecycle()
                val threshold by vm.largeThreshold.collectAsStateWithLifecycle()
                val categories by vm.categories.collectAsStateWithLifecycle()
                val selectedTx by vm.selectedTransaction.collectAsStateWithLifecycle()
                val categorySelection by vm.categorySelection.collectAsStateWithLifecycle()
                val categoryTxs by vm.categorySheetTransactions.collectAsStateWithLifecycle()

                Scaffold(
                    containerColor = Ink0,
                    bottomBar = {
                        NavigationBar(containerColor = Ink0) {
                            Screen.entries.forEach { s ->
                                NavigationBarItem(
                                    selected = route == s.route,
                                    onClick = {
                                        nav.navigate(s.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(s.icon, contentDescription = s.label) },
                                    // Labels only on the selected tab so large font sizes never overlap.
                                    alwaysShowLabel = false,
                                    label = {
                                        Text(s.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                        indicatorColor = Emerald,
                                        selectedTextColor = Emerald,
                                        unselectedIconColor = TextMid,
                                        unselectedTextColor = TextMid,
                                    ),
                                )
                            }
                        }
                    },
                ) { padding ->
                    NavHost(nav, startDestination = Screen.Home.route, modifier = Modifier.fillMaxSize().padding(padding)) {
                        composable(Screen.Home.route) { HomeScreen(vm) }
                        composable(Screen.Insights.route) { InsightsScreen(vm) }
                        composable(Screen.Transactions.route) { TransactionsScreen(vm) }
                        composable(Screen.Categories.route) { CategoriesScreen(vm) }
                        composable(Screen.Settings.route) { SettingsScreen(vm) }
                    }
                }

                // Only one sheet at a time: the transaction editor temporarily replaces the day or
                // category sheet it was opened from, which comes back when the editor closes.
                val editing = selectedTx != null
                if (!editing && categorySelection == null) selectedDay?.let { day ->
                    DayDetailSheet(
                        day = day,
                        summary = daySummary?.takeIf { it.date == day },
                        today = LocalDate.now(UAE_ZONE),
                        largeThresholdMinor = threshold,
                        onDismiss = vm::closeDay,
                        onChangeDay = vm::openDay,
                        onTransactionClick = { vm.openTransaction(it.tx.id) },
                    )
                }

                if (!editing) categorySelection?.let { sel ->
                    CategorySheet(
                        category = categories.firstOrNull { it.id == sel.categoryId },
                        cycle = sel.cycle,
                        transactions = categoryTxs,
                        largeThresholdMinor = threshold,
                        onDismiss = vm::closeCategory,
                        onTransactionClick = { vm.openTransaction(it.tx.id) },
                    )
                }

                selectedTx?.let { item ->
                    TransactionSheet(
                        item = item,
                        categories = categories,
                        onDismiss = vm::closeTransaction,
                        onSetCategory = { categoryId, applyToMerchant ->
                            val merchant = item.tx.merchant
                            if (applyToMerchant && merchant != null) vm.mapMerchantToCategory(merchant, categoryId)
                            else vm.updateTransactionCategory(item.tx.id, categoryId)
                            vm.closeTransaction()
                        },
                        onDelete = {
                            vm.deleteTransaction(item.tx.id)
                            vm.closeTransaction()
                        },
                    )
                }
            }
        }
    }
}
