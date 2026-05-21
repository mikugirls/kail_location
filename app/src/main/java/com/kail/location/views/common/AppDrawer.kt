package com.kail.location.views.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kail.location.R
import com.kail.location.auth.AuthManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawer(
    drawerState: DrawerState,
    currentScreen: String,
    onNavigate: (Int) -> Unit,
    appVersion: String,
    runMode: String,
    onRunModeChange: (String) -> Unit,
    onDeveloperModeSelected: () -> Unit = {},
    onXposedSettingsSelected: () -> Unit = {},
    scope: kotlinx.coroutines.CoroutineScope = rememberCoroutineScope()
) {
    var showRunModeDialog by remember { mutableStateOf(false) }
    var showEnvDialog by remember { mutableStateOf(false) }
    var showXposedDownloadDialog by remember { mutableStateOf(false) }
    var showLoginActivity by remember { mutableStateOf(false) }
    var envMessage by remember { mutableStateOf("") }
    val context = LocalContext.current

    var isLoggedIn by remember { mutableStateOf(AuthManager.isLoggedIn) }
    val userEmail by remember { mutableStateOf(AuthManager.email ?: "") }

    if (showLoginActivity) {
        val intent = Intent(context, com.kail.location.views.auth.LoginActivity::class.java)
        context.startActivity(intent)
        showLoginActivity = false
    }

    fun isXposedModuleInstalled(): Boolean {
        return try {
            val pm = context.packageManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo("com.kail.locationxposed", android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo("com.kail.locationxposed", 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openXposedDownloadPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/noellegazelle6/kail_location/releases"))
        context.startActivity(intent)
    }

    if (showRunModeDialog) {
        AlertDialog(
            onDismissRequest = { showRunModeDialog = false },
            title = { Text(stringResource(R.string.run_mode_dialog_title)) },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onRunModeChange("root")
                                showRunModeDialog = false
                            }
                            .padding(16.dp)
                    ) {
                        RadioButton(
                            selected = runMode == "root",
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.run_mode_root))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showRunModeDialog = false
                                onDeveloperModeSelected()
                            }
                            .padding(16.dp)
                    ) {
                        RadioButton(
                            selected = runMode == "developer",
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.run_mode_developer))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showRunModeDialog = false
                                if (isXposedModuleInstalled()) {
                                    onRunModeChange("xposed")
                                } else {
                                    showXposedDownloadDialog = true
                                }
                            }
                            .padding(16.dp)
                    ) {
                        RadioButton(
                            selected = runMode == "xposed",
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.run_mode_xposed))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onRunModeChange("sandbox")
                                showRunModeDialog = false
                            }
                            .padding(16.dp)
                    ) {
                        RadioButton(
                            selected = runMode == "sandbox",
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.run_mode_sandbox))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRunModeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
    
    if (showEnvDialog) {
        AlertDialog(
            onDismissRequest = { showEnvDialog = false },
            title = { Text(stringResource(R.string.drawer_env_check)) },
            text = { Text(envMessage) },
            confirmButton = {
                TextButton(onClick = { showEnvDialog = false }) {
                    Text(stringResource(R.string.drawer_ok))
                }
            }
        )
    }

    if (showXposedDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showXposedDownloadDialog = false },
            title = { Text(stringResource(R.string.xposed_module_not_found)) },
            text = { Text(stringResource(R.string.xposed_module_download_hint)) },
            dismissButton = {
                TextButton(onClick = { showXposedDownloadDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showXposedDownloadDialog = false
                    openXposedDownloadPage()
                }) {
                    Text(stringResource(R.string.xposed_module_download))
                }
            }
        )
    }

    ModalDrawerSheet {
        LazyColumn {
            item { DrawerHeader(appVersion, onLoginClick = { showLoginActivity = true }) }
            item { HorizontalDivider() }

            if (isLoggedIn) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.drawer_logged_in_as),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = userEmail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = {
                                AuthManager.clearAuth()
                                isLoggedIn = false
                            }
                        ) {
                            Text(stringResource(R.string.drawer_action_logout))
                        }
                    }
                }
                item { HorizontalDivider() }
            }

            // ===== Group: 模拟 =====
            item {
                Text(
                    text = stringResource(R.string.nav_menu_sim_group),
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_menu_location_simulation)) },
                    icon = { Icon(painterResource(R.drawable.ic_position), contentDescription = null) },
                    selected = currentScreen == "LocationSimulation",
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_location_simulation) } }
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_menu_route_simulation)) },
                    icon = { Icon(painterResource(R.drawable.ic_move), contentDescription = null) },
                    selected = currentScreen == "RouteSimulation",
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_route_simulation) } }
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.drawer_nav_sim)) },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    selected = currentScreen == "NavigationSimulation",
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_navigation_simulation) } }
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.drawer_nfc_sim)) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    selected = currentScreen == "NfcSimulation",
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_nfc_simulation) } }
                )
            }
            if (runMode == "root") {
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.nav_menu_independent_sim)) },
                        icon = { Icon(painterResource(R.drawable.ic_position), contentDescription = null) },
                        selected = currentScreen == "IndependentSimulation",
                        onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_independent_simulation) } }
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.nav_menu_wifi_sim)) },
                        icon = { Icon(painterResource(R.drawable.ic_menu_settings), contentDescription = null) },
                        selected = currentScreen == "WifiSimulation",
                        onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_wifi_simulation) } }
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.nav_menu_cell_sim)) },
                        icon = { Icon(painterResource(R.drawable.ic_menu_dev), contentDescription = null) },
                        selected = currentScreen == "CellSimulation",
                        onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_cell_simulation) } }
                    )
                }
            }
            if (runMode == "sandbox") {
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_sandbox)) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        selected = currentScreen == "Sandbox",
                        onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_sandbox) } }
                    )
                }
            }
            if (runMode == "xposed") {
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_xposed_settings)) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close(); onXposedSettingsSelected() } }
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // ===== Group: 设置 =====
            item {
                Text(
                    text = stringResource(R.string.nav_menu_settings),
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_menu_settings)) },
                    icon = { Icon(painterResource(R.drawable.ic_menu_settings), contentDescription = null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_settings) } }
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.drawer_run_mode)) },
                    icon = { Icon(painterResource(R.drawable.ic_menu_dev), contentDescription = null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); showRunModeDialog = true } }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            // ===== Group: 更多 =====
            item {
                Text(
                    text = stringResource(R.string.nav_menu_more),
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_menu_contact)) },
                    icon = { Icon(painterResource(R.drawable.ic_contact), contentDescription = null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_contact) } }
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_menu_sponsor)) },
                    icon = { Icon(painterResource(R.drawable.ic_user), contentDescription = null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_sponsor) } }
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.nav_menu_github)) },
                    icon = { Icon(painterResource(R.drawable.ic_menu_dev), contentDescription = null) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close(); onNavigate(R.id.nav_source_code) } }
                )
            }
        }
    }
}
