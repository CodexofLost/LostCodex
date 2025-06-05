package com.save.me

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: Activity,
    onSetupClick: (() -> Unit)? = null,
    onRefreshClick: (() -> Unit)? = null,
    vm: MainViewModel = viewModel(),
    realPermissions: List<PermissionStatus>,
    requestPermission: (String) -> Unit,
    openAppSettings: () -> Unit,
    requestOverlayPermission: () -> Unit,
    requestAllFilesPermission: () -> Unit,
    requestBatteryPermission: () -> Unit,
    showTitle: Boolean = true,
    permissionsUiRefresh: Int
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val nickname = vm.getCurrentNickname()
    val botToken = vm.getCurrentBotToken()
    val serviceActive by vm.serviceActive
    val actionInProgress by vm.actionInProgress
    val actionError by vm.actionError

    var fcmStatus by remember { mutableStateOf(false) }
    var telegramStatus by remember { mutableStateOf(false) }

    // Connection status refresh logic
    val refreshConnectionStatuses = {
        scope.launch {
            // FCM Status: get actual token from FirebaseMessaging
            try {
                val token = withContext(Dispatchers.IO) {
                    FirebaseMessaging.getInstance().token.await()
                }
                fcmStatus = token.isNotBlank()
            } catch (e: Exception) {
                fcmStatus = false
            }
            // Telegram Bot API Status /getMe
            if (botToken.isNotBlank()) {
                val (ok, _) = checkTelegramBotApi(botToken)
                telegramStatus = ok
            } else {
                telegramStatus = false
            }
        }
    }

    // Call refresh on permission or refresh click
    LaunchedEffect(permissionsUiRefresh) {
        refreshConnectionStatuses()
    }
    // Also on first launch
    LaunchedEffect(Unit) {
        refreshConnectionStatuses()
    }

    // Ensure service status is refreshed when screen is shown or after actions
    LaunchedEffect(actionInProgress) {
        vm.refreshServiceStatus()
    }

    // Show error as snackbar
    LaunchedEffect(actionError) {
        actionError?.let { errorMsg ->
            scope.launch {
                val res = snackbarHostState.showSnackbar(errorMsg, actionLabel = "Retry")
                if (res == SnackbarResult.ActionPerformed) {
                    actionInProgress?.type?.let { vm.startRemoteAction(it) }
                }
                vm.clearError()
            }
        }
    }

    // Start the permission tab as collapsed
    var permissionsExpanded by remember { mutableStateOf(false) }

    // If you want the header bar with refresh/settings here (standalone), use this:
    if (showTitle) {
        var refreshAnimating by remember { mutableStateOf(false) }
        var gearAnimating by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Find My Device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
            )
            AnimatedRotateIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "Refresh Status",
                isRotating = refreshAnimating,
                onClick = {
                    refreshAnimating = true
                    onRefreshClick?.invoke()
                },
                onAnimationEnd = { refreshAnimating = false }
            )
            AnimatedRotateIconButton(
                icon = Icons.Filled.Edit,
                contentDescription = "Setup Device",
                isRotating = gearAnimating,
                onClick = {
                    gearAnimating = true
                    onSetupClick?.invoke()
                },
                onAnimationEnd = { gearAnimating = false }
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(0.dp)
    ) {
        // Device Username Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Device Username",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onSetupClick?.invoke() }
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit Nickname")
            }
        }
        // Nickname Card - stretched to full width
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = nickname,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Bot Token Row (with pencil at end)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Device Token",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onSetupClick?.invoke() }
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit bot token")
            }
        }
        // Bot Token Card - stretched to full width, token inside horizontal scroll if needed
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = botToken,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Connection Status Section
        Text(
            "Connection Status:",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        // FCM Status row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StatusDot(fcmStatus)
            Spacer(Modifier.width(8.dp))
            Text("FCM")
            Spacer(Modifier.width(8.dp))
            Text(": ${if (fcmStatus) "Connected" else "Not Connected"}")
        }
        // Telegram Bot status row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StatusDot(telegramStatus)
            Spacer(Modifier.width(8.dp))
            Text("Telegram Bot API")
            Spacer(Modifier.width(8.dp))
            Text(": ${if (telegramStatus) "Connected" else "Not Connected"}")
        }
        // Service Status row - same style as above, only circle is colored
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StatusDot(serviceActive)
            Spacer(Modifier.width(8.dp))
            Text("Service Status")
            Spacer(Modifier.width(8.dp))
            Text(": ${if (serviceActive) "Active" else "Inactive"}")
        }

        Spacer(Modifier.height(20.dp))

        // Expandable Permission Status Section with Grant button
        PermissionStatusExpandableTab(
            activity = activity,
            realPermissions = realPermissions,
            expanded = permissionsExpanded,
            onExpandToggle = { permissionsExpanded = !permissionsExpanded },
            requestPermission = requestPermission,
            openAppSettings = openAppSettings,
            requestOverlayPermission = requestOverlayPermission,
            requestAllFilesPermission = requestAllFilesPermission,
            requestBatteryPermission = requestBatteryPermission
        )

        Spacer(Modifier.height(20.dp))

        // In-progress action
        actionInProgress?.let { action ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                CircularProgressIndicator(
                    Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Running: ${action.type.replaceFirstChar { it.uppercase() }}",
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun AnimatedRotateIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isRotating: Boolean,
    onClick: () -> Unit,
    onAnimationEnd: () -> Unit
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isRotating) {
        if (isRotating) {
            rotation.snapTo(0f)
            rotation.animateTo(
                360f,
                animationSpec = tween(600)
            )
            onAnimationEnd()
        }
    }
    IconButton(
        onClick = {
            if (!isRotating) onClick()
        }
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    rotationZ = rotation.value
                }
        )
    }
}

@Composable
fun PermissionStatusExpandableTab(
    activity: Activity,
    realPermissions: List<PermissionStatus>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    requestPermission: (String) -> Unit,
    openAppSettings: () -> Unit,
    requestOverlayPermission: () -> Unit,
    requestAllFilesPermission: () -> Unit,
    requestBatteryPermission: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandToggle() }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    "Permissions Status",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    realPermissions.forEach { status ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = if (status.granted) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                tint = if (status.granted) Color(0xFF4CAF50) else Color(0xFFF44336)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(status.name, modifier = Modifier.weight(1f))
                            if (!status.granted) {
                                when (status.name) {
                                    "Overlay (Draw over apps)" -> {
                                        TextButton(onClick = requestOverlayPermission) { Text("Grant") }
                                    }
                                    "All Files Access" -> {
                                        TextButton(onClick = requestAllFilesPermission) { Text("Grant") }
                                    }
                                    "Ignore Battery Optimization" -> {
                                        TextButton(onClick = requestBatteryPermission) { Text("Grant") }
                                    }
                                    else -> {
                                        val systemPermission = PermissionsAndOnboarding.getSystemPermissionFromLabel(status.name)
                                        val canRequest = systemPermission != null && PermissionsAndOnboarding.canRequestPermission(activity, systemPermission)
                                        TextButton(onClick = {
                                            if (canRequest && systemPermission != null) {
                                                requestPermission(systemPermission)
                                            } else {
                                                openAppSettings()
                                            }
                                        }) { Text("Grant") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusDot(active: Boolean) {
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF4CAF50) else Color(0xFFF44336))
    )
}

// Helper: Check Telegram Bot API connection using getMe
suspend fun checkTelegramBotApi(token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("https://api.telegram.org/bot${token}/getMe")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        if (code == 200 && text.contains("\"ok\":true")) {
            return@withContext true to "OK"
        } else {
            return@withContext false to "API Error"
        }
    } catch (e: Exception) {
        return@withContext false to (e.localizedMessage ?: "Network error")
    }
}