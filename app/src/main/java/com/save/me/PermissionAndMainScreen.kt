package com.save.me

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionAndMainScreen(
    activity: Activity,
    permissionsUiRefresh: Int,
    onSetupClick: () -> Unit,
    vm: MainViewModel,
    requestPermission: (String) -> Unit,
    openAppSettings: () -> Unit,
    requestOverlayPermission: () -> Unit,
    requestAllFilesPermission: () -> Unit,
    requestBatteryPermission: () -> Unit
) {
    val context = LocalContext.current
    var permissionStatuses by remember { mutableStateOf(PermissionsAndOnboarding.getAllPermissionStatuses(context)) }
    var needsOverlay by remember { mutableStateOf(PermissionsAndOnboarding.needsOverlay(context)) }
    var needsAllFiles by remember { mutableStateOf(PermissionsAndOnboarding.needsAllFiles(context)) }
    var needsBattery by remember { mutableStateOf(PermissionsAndOnboarding.needsBattery(context)) }
    var needsBgLocation by remember { mutableStateOf(!PermissionsAndOnboarding.hasBackgroundLocation(context)) }
    var refreshKey by remember { mutableStateOf(0) }

    val handleRefresh = {
        // Refresh permission statuses
        permissionStatuses = PermissionsAndOnboarding.getAllPermissionStatuses(context)
        needsOverlay = PermissionsAndOnboarding.needsOverlay(context)
        needsAllFiles = PermissionsAndOnboarding.needsAllFiles(context)
        needsBattery = PermissionsAndOnboarding.needsBattery(context)
        needsBgLocation = !PermissionsAndOnboarding.hasBackgroundLocation(context)
        // Trigger connection status refresh in MainScreen via refreshKey
        refreshKey += 1
    }

    LaunchedEffect(permissionsUiRefresh) {
        handleRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find My Device") },
                actions = {
                    var refreshAnimating by remember { mutableStateOf(false) }
                    var gearAnimating by remember { mutableStateOf(false) }
                    AnimatedRotateIconButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "Refresh Status",
                        isRotating = refreshAnimating,
                        onClick = {
                            refreshAnimating = true
                            handleRefresh()
                        },
                        onAnimationEnd = { refreshAnimating = false }
                    )
                    AnimatedRotateIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = "Setup Device",
                        isRotating = gearAnimating,
                        onClick = {
                            gearAnimating = true
                            onSetupClick()
                        },
                        onAnimationEnd = { gearAnimating = false }
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            MainScreen(
                activity = activity,
                onSetupClick = onSetupClick,
                onRefreshClick = handleRefresh,
                vm = vm,
                realPermissions = permissionStatuses,
                requestPermission = requestPermission,
                openAppSettings = openAppSettings,
                requestOverlayPermission = requestOverlayPermission,
                requestAllFilesPermission = requestAllFilesPermission,
                requestBatteryPermission = requestBatteryPermission,
                showTitle = false,
                permissionsUiRefresh = refreshKey // Will trigger MainScreen's refresh logic
            )
        }
    }
}