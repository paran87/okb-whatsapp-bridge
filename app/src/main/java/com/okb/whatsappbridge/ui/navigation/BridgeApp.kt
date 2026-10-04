package com.okb.whatsappbridge.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.okb.whatsappbridge.AppContainer
import com.okb.whatsappbridge.ui.StatusViewModel
import com.okb.whatsappbridge.ui.dashboard.DashboardScreen
import com.okb.whatsappbridge.ui.diagnostics.DiagnosticsScreen
import com.okb.whatsappbridge.ui.diagnostics.LogsScreen
import com.okb.whatsappbridge.ui.groups.GroupsScreen
import com.okb.whatsappbridge.ui.groups.GroupsViewModel
import com.okb.whatsappbridge.ui.messages.MediaDetailScreen
import com.okb.whatsappbridge.ui.messages.MediaDetailViewModel
import com.okb.whatsappbridge.ui.messages.MessagesScreen
import com.okb.whatsappbridge.ui.messages.MessagesViewModel
import com.okb.whatsappbridge.ui.settings.SettingsScreen
import com.okb.whatsappbridge.ui.sync.SyncScreen
import com.okb.whatsappbridge.ui.theme.MonoFamily
import kotlinx.coroutines.delay

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard),
    MESSAGES("messages", "Messages", Icons.AutoMirrored.Filled.Chat),
    GROUPS("groups", "Groups", Icons.Filled.Groups),
    SYNC("sync", "Sync", Icons.Filled.Sync),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

private const val ROUTE_DIAGNOSTICS = "diagnostics"
private const val ROUTE_LOGS = "logs"
private const val ROUTE_MESSAGE_DETAIL = "message"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeApp(container: AppContainer) {
    val navController = rememberNavController()
    val statusViewModel: StatusViewModel = viewModel(factory = viewModelFactory { initializer { StatusViewModel(container) } })
    val state by statusViewModel.state.collectAsStateWithLifecycle()
    val busy by statusViewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Re-read system state whenever the operator returns (e.g. from Android Settings).
    LifecycleResumeEffect(Unit) {
        statusViewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { statusViewModel.events.collect { snackbar.showSnackbar(it) } }

    // Clock for "2 minutes ago" labels; ticks only while the UI is visible.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = TopLevel.entries.firstOrNull { it.route == currentRoute }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    fun navigateTop(dest: TopLevel) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (wide) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                TopLevel.entries.forEach { dest ->
                    NavigationRailItem(
                        selected = topLevel == dest,
                        onClick = { navigateTop(dest) },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.label) },
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        navigationIcon = {
                            if (topLevel == null) {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    "OKB WHATSAPP BRIDGE",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    when (currentRoute) {
                                        ROUTE_DIAGNOSTICS -> "Diagnostics"
                                        ROUTE_LOGS -> "Event log"
                                        else -> if (currentRoute?.startsWith(ROUTE_MESSAGE_DETAIL) == true) "Message detail" else topLevel?.label ?: ""
                                    } + "  ·  " + state.polled.deviceId,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = MonoFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        TopLevel.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = topLevel == dest,
                                onClick = { navigateTop(dest) },
                                icon = { Icon(dest.icon, contentDescription = null) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = TopLevel.DASHBOARD.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(TopLevel.DASHBOARD.route) {
                    DashboardScreen(
                        state = state,
                        now = now,
                        onToggleMonitoring = statusViewModel::setMonitoringEnabled,
                        onTestBackend = statusViewModel::testBackend,
                        onOpenGroups = { navigateTop(TopLevel.GROUPS) },
                        onOpenSettings = { navigateTop(TopLevel.SETTINGS) },
                        onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                        onReconnectListener = statusViewModel::reconnectListener,
                    )
                }
                composable(TopLevel.MESSAGES.route) {
                    val vm: MessagesViewModel = viewModel(
                        factory = viewModelFactory { initializer { MessagesViewModel(container.messageRepository, container.mediaRepository) } },
                    )
                    MessagesScreen(vm, onOpenDetail = { id -> navController.navigate("$ROUTE_MESSAGE_DETAIL/$id") })
                }
                composable("$ROUTE_MESSAGE_DETAIL/{id}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val vm: MediaDetailViewModel = viewModel(
                        factory = viewModelFactory { initializer { MediaDetailViewModel(id, container.messageRepository, container.mediaRepository) } },
                    )
                    MediaDetailScreen(vm)
                }
                composable(TopLevel.GROUPS.route) {
                    val vm: GroupsViewModel = viewModel(
                        factory = viewModelFactory { initializer { GroupsViewModel(container.groupRepository, container.logger) } },
                    )
                    GroupsScreen(vm)
                }
                composable(TopLevel.SYNC.route) {
                    SyncScreen(
                        state = state,
                        onSyncNow = statusViewModel::syncNow,
                        onRetryFailed = statusViewModel::retryFailed,
                        onSyncMediaNow = statusViewModel::syncMediaNow,
                        onRetryFailedMedia = statusViewModel::retryFailedMedia,
                        onSetSyncPaused = statusViewModel::setSyncPaused,
                    )
                }
                composable(TopLevel.SETTINGS.route) {
                    SettingsScreen(
                        state = state,
                        busy = busy,
                        onToggleMonitoring = statusViewModel::setMonitoringEnabled,
                        onSaveBackend = statusViewModel::saveBackendConfig,
                        onSaveDeviceName = statusViewModel::saveDeviceName,
                        onRegisterDevice = statusViewModel::registerDevice,
                        onTestBackend = statusViewModel::testBackend,
                        onSetSyncPaused = statusViewModel::setSyncPaused,
                        onSetCaptureMedia = statusViewModel::setCaptureMedia,
                        onSetDeleteLocalAfterUpload = statusViewModel::setDeleteLocalAfterUpload,
                        onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                        onRefresh = statusViewModel::refresh,
                    )
                }
                composable(ROUTE_DIAGNOSTICS) {
                    DiagnosticsScreen(
                        state = state,
                        now = now,
                        busy = busy,
                        onTestBackend = statusViewModel::testBackend,
                        onRunHealthCheck = statusViewModel::runHealthCheck,
                        onViewLogs = { navController.navigate(ROUTE_LOGS) },
                    )
                }
                composable(ROUTE_LOGS) { LogsScreen(container.logRepository) }
            }
        }
    }
}
