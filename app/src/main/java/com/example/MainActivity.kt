package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sensor.ImuData
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(dynamicColor = false) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    containerColor = Color(0xFF1C1B1F) // Elegant Dark Surface Background
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val imuState by viewModel.imuState.collectAsStateWithLifecycle()
    val isSimulating by viewModel.isSimulating.collectAsStateWithLifecycle()
    
    val isServerRunning by viewModel.isServerRunning.collectAsStateWithLifecycle()
    val serverLogs by viewModel.serverLogs.collectAsStateWithLifecycle()
    val lastServedJson by viewModel.lastServedJson.collectAsStateWithLifecycle()
    val lastReceivedJson by viewModel.lastReceivedJson.collectAsStateWithLifecycle()
    
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val successCount by viewModel.successCount.collectAsStateWithLifecycle()
    val errorCount by viewModel.errorCount.collectAsStateWithLifecycle()
    val streamLogs by viewModel.streamLogs.collectAsStateWithLifecycle()
    val lastPostPayload by viewModel.lastPostPayload.collectAsStateWithLifecycle()
    
    val targetUrl by viewModel.targetUrl.collectAsStateWithLifecycle()
    val streamIntervalMs by viewModel.streamIntervalMs.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Elegant violet-themed subtle glowing background gradients
                drawCircle(
                    color = Color(0xFFD0BCFF).copy(alpha = 0.08f),
                    radius = size.width * 1.2f,
                    center = Offset(size.width * 0.9f, size.height * 0.1f)
                )
                drawCircle(
                    color = Color(0xFF381E72).copy(alpha = 0.12f),
                    radius = size.width * 1.0f,
                    center = Offset(size.width * 0.1f, size.height * 0.8f)
                )
            }
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- HEADER SECTION ---
        HeaderComponent(
            isStreaming = isStreaming,
            isServerRunning = isServerRunning,
            isSimulating = isSimulating
        )

        // --- MODE TOGGLE (TAB SWITCHER) ---
        TabSwitcherComponent(
            selectedTab = selectedTab,
            onTabSelected = { viewModel.setSelectedTab(it) }
        )

        // --- DYNAMIC CONTENT BASED ON SELECTED TAB ---
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                slideInHorizontally { width -> if (targetState > initialState) width else -width } + fadeIn() togetherWith
                slideOutHorizontally { width -> if (targetState > initialState) -width else width } + fadeOut()
            },
            label = "tab_content_transition"
        ) { tabIndex ->
            when (tabIndex) {
                0 -> {
                    // TAB 1: SENSOR STREAM CONTROLS
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SensorMetricVisualizer(
                            imuData = imuState,
                            isSimulating = isSimulating,
                            onToggleSimulation = { viewModel.toggleSensorSimulation() }
                        )

                        StreamConfigurationCard(
                            targetUrl = targetUrl,
                            streamIntervalMs = streamIntervalMs,
                            isStreaming = isStreaming,
                            successCount = successCount,
                            errorCount = errorCount,
                            lastPayload = lastPostPayload,
                            onUrlChange = { viewModel.updateTargetUrl(it) },
                            onIntervalChange = { viewModel.updateStreamInterval(it) },
                            onToggleStream = {
                                keyboardController?.hide()
                                viewModel.toggleStreaming()
                            },
                            onClearStats = { viewModel.clearClientStats() }
                        )

                        TerminalLogCard(
                            title = "Outgoing Stream Logs",
                            logs = streamLogs,
                            testTag = "stream_terminal"
                        )
                    }
                }
                1 -> {
                    // TAB 2: EMBEDDED SERVER & DATA VIEW
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        WebserverControlCard(
                            isServerRunning = isServerRunning,
                            getIps = { viewModel.getLocalIpAddresses() },
                            onToggleServer = {
                                val result = viewModel.toggleWebserver()
                                if (result != "Success" && result != "Server stopped") {
                                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                }
                            },
                            onCopyUrl = { url ->
                                clipboardManager.setText(AnnotatedString(url))
                                Toast.makeText(context, "Copied URL: $url", Toast.LENGTH_SHORT).show()
                            }
                        )

                        // Displays JSON currently served (GET /sensor-data)
                        DataViewCard(
                            title = "Raw JSON Telemetry (To Be Served)",
                            jsonContent = if (lastServedJson.isEmpty()) imuState.toJsonString() else lastServedJson,
                            badgeColor = Color(0xFFD0BCFF),
                            badgeText = "GET /sensor-data",
                            infoText = "This is the current state that local web clients receive when fetching.",
                            testTag = "served_json_view"
                        )

                        // Displays JSON received via POST to /post-data if applicable
                        DataViewCard(
                            title = "Raw JSON Telemetry (Last Received)",
                            jsonContent = if (lastReceivedJson.isEmpty()) """{"message": "No external POST received yet.", "route": "POST /post-data"}""" else lastReceivedJson,
                            badgeColor = Color(0xFFB3261E),
                            badgeText = "POST /post-data",
                            infoText = "External applications can HTTP POST raw JSON here to verify connectivity.",
                            testTag = "received_json_view"
                        )

                        TerminalLogCard(
                            title = "Embedded Server Activity Logs",
                            logs = serverLogs,
                            testTag = "server_terminal"
                        )
                    }
                }
            }
        }

        // --- BOTTOM STATUS BAR (FOOTER) ---
        FooterStatusBar(
            targetUrl = targetUrl,
            streamIntervalMs = streamIntervalMs,
            isStreaming = isStreaming
        )
    }
}

// --- SUB-COMPONENTS ---

@Composable
fun HeaderComponent(
    isStreaming: Boolean,
    isServerRunning: Boolean,
    isSimulating: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD0BCFF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Sensors Node",
                    tint = Color(0xFF381E72),
                    modifier = Modifier.size(24.dp)
                )
            }
            Column {
                Text(
                    text = "IMU Node",
                    color = Color(0xFFE6E1E5),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isStreaming) "STREAM ACTIVE" else if (isServerRunning) "SERVER RUNNING" else "SERVICE IDLE",
                    color = if (isStreaming || isServerRunning) Color(0xFF7DFFB3) else Color(0xFFCAC4D0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Live connection pill indicators with Elegant Dark styling
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(
                label = "STREAM",
                isActive = isStreaming,
                activeColor = Color(0xFFD0BCFF)
            )
            StatusPill(
                label = "SERVER",
                isActive = isServerRunning,
                activeColor = Color(0xFFE8DEF8)
            )
        }
    }
}

@Composable
fun StatusPill(label: String, isActive: Boolean, activeColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulsing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pill_alpha"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) activeColor.copy(alpha = 0.15f) else Color(0xFF2B2930))
            .border(
                width = 1.dp,
                color = if (isActive) activeColor else Color(0xFF49454F),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        color = if (isActive) activeColor.copy(alpha = alpha) else Color(0xFF938F99)
                    )
            )
            Text(
                text = label,
                color = if (isActive) Color.White else Color(0xFFCAC4D0),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TabSwitcherComponent(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)) // Fully rounded capsule
            .background(Color.Transparent)
            .border(
                width = 1.dp,
                color = Color(0xFF938F99),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = listOf("Sensor Stream", "Server & Data View")
        tabs.forEachIndexed { index, title ->
            val isSelected = selectedTab == index
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFFE8DEF8) else Color.Transparent,
                animationSpec = tween(150),
                label = "tab_bg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFF1D192B) else Color(0xFFCAC4D0),
                animationSpec = tween(150),
                label = "tab_text"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(bgColor)
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 10.dp)
                    .testTag("tab_$index"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun FooterStatusBar(
    targetUrl: String,
    streamIntervalMs: Long,
    isStreaming: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2B2930))
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .drawBehind {
                        drawCircle(
                            color = if (isStreaming) Color(0xFF7DFFB3).copy(alpha = 0.4f * glowScale) else Color(0xFF938F99).copy(alpha = 0.3f),
                            radius = size.width * (1.2f + 1.2f * glowScale)
                        )
                    }
                    .clip(CircleShape)
                    .background(if (isStreaming) Color(0xFF7DFFB3) else Color(0xFF938F99))
            )
            
            Text(
                text = if (isStreaming) "TX: ${targetUrl.take(28)}${if (targetUrl.length > 28) "..." else ""}" else "TX DISCONNECTED",
                color = Color(0xFFCAC4D0),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Syncing",
                tint = Color(0xFFD0BCFF),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "${"%.1f".format(1000f / streamIntervalMs.toFloat())}Hz",
                color = Color(0xFFD0BCFF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}


@Composable
fun OrientationVisualizer(
    imuData: ImuData,
    modifier: Modifier = Modifier
) {
    // Map acceleration to rotational degrees for visual simulation
    val rotX by animateFloatAsState(
        targetValue = (imuData.accelY * -9f).coerceIn(-90f, 90f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "rot_x"
    )
    val rotY by animateFloatAsState(
        targetValue = (imuData.accelX * 9f).coerceIn(-90f, 90f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "rot_y"
    )
    val rotZ by animateFloatAsState(
        targetValue = (imuData.gyroZ * 50f).coerceIn(-180f, 180f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "rot_z"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2B2930))
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
            .drawBehind {
                // Draw digital grid background pattern matching the HTML
                val strokeWidth = 0.5f
                val dashPathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(10f, 10f),
                    0f
                )
                val gridStep = 40.dp.toPx()
                for (x in 0..size.width.toInt() step gridStep.toInt()) {
                    drawLine(
                        color = Color(0xFFD0BCFF).copy(alpha = 0.08f),
                        start = Offset(x.toFloat(), 0f),
                        end = Offset(x.toFloat(), size.height),
                        strokeWidth = strokeWidth,
                        pathEffect = dashPathEffect
                    )
                }
                for (y in 0..size.height.toInt() step gridStep.toInt()) {
                    drawLine(
                        color = Color(0xFFD0BCFF).copy(alpha = 0.08f),
                        start = Offset(0f, y.toFloat()),
                        end = Offset(size.width, y.toFloat()),
                        strokeWidth = strokeWidth,
                        pathEffect = dashPathEffect
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Holographic Glowing 3D Board
        Box(
            modifier = Modifier
                .size(70.dp)
                .graphicsLayer {
                    rotationX = rotX
                    rotationY = rotY
                    rotationZ = rotZ + 15f // extra 15deg base rotation like the HTML design
                    cameraDistance = 8 * density
                }
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFD0BCFF), Color(0xFFD0BCFF).copy(alpha = 0.3f))
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFD0BCFF).copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner axis indicators
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD0BCFF))
            )
        }

        // Live labels
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "3D ORIENTATION LIVE",
                color = Color(0xFFD0BCFF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart)
            )
            
            Text(
                text = "P: ${"%.1f°".format(rotX)} | R: ${"%.1f°".format(rotY)}",
                color = Color(0xFFCAC4D0),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun SensorMetricVisualizer(
    imuData: ImuData,
    isSimulating: Boolean,
    onToggleSimulation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF49454F))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Title and fallback badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Real-time IMU Feeds",
                        color = Color(0xFFE6E1E5),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onToggleSimulation,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Force simulated data",
                            tint = Color(0xFFD0BCFF)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSimulating) Color(0xFFD97706).copy(alpha = 0.15f) else Color(0xFF7DFFB3).copy(alpha = 0.15f))
                        .border(
                            1.dp,
                            if (isSimulating) Color(0xFFD97706) else Color(0xFF7DFFB3),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isSimulating) "SIMULATION ACTIVE" else "HARDWARE SENSORS",
                        color = if (isSimulating) Color(0xFFFBBF24) else Color(0xFF7DFFB3),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Live 3D box model
            OrientationVisualizer(imuData = imuData)

            // Metrics Grid (Accelerometer & Gyroscope)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Accelerometer Column
                SensorMetricBlock(
                    modifier = Modifier.weight(1f),
                    title = "ACCELEROMETER",
                    subtitle = "m/s² (with gravity)",
                    colorTheme = Color(0xFFD0BCFF),
                    valX = imuData.accelX,
                    valY = imuData.accelY,
                    valZ = imuData.accelZ,
                    maxExpected = 20f
                )

                // Gyroscope Column
                SensorMetricBlock(
                    modifier = Modifier.weight(1f),
                    title = "GYROSCOPE",
                    subtitle = "rad/s (angular velocity)",
                    colorTheme = Color(0xFFE8DEF8),
                    valX = imuData.gyroX,
                    valY = imuData.gyroY,
                    valZ = imuData.gyroZ,
                    maxExpected = 5f
                )
            }
        }
    }
}

@Composable
fun SensorMetricBlock(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    colorTheme: Color,
    valX: Float,
    valY: Float,
    valZ: Float,
    maxExpected: Float
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1B1F))
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column {
            Text(text = title, color = colorTheme, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(text = subtitle, color = Color(0xFFCAC4D0), fontSize = 9.sp)
        }

        HorizontalDivider(color = Color(0xFF49454F), thickness = 0.5.dp)

        // Axis Channels X, Y, Z
        ChannelComponent(axis = "X", value = valX, maxExpected = maxExpected, colorTheme = colorTheme)
        ChannelComponent(axis = "Y", value = valY, maxExpected = maxExpected, colorTheme = colorTheme)
        ChannelComponent(axis = "Z", value = valZ, maxExpected = maxExpected, colorTheme = colorTheme)
    }
}

@Composable
fun ChannelComponent(
    axis: String,
    value: Float,
    maxExpected: Float,
    colorTheme: Color
) {
    // Math logic to calculate bar progress symmetrically
    val halfMax = maxExpected / 2f
    val progress = ((value + halfMax) / maxExpected).coerceIn(0f, 1f)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = axis,
                color = Color(0xFFCAC4D0),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "%.3f".format(value),
                color = Color(0xFFE6E1E5),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = colorTheme,
            trackColor = Color(0xFF2B2930)
        )
    }
}

@Composable
fun StreamConfigurationCard(
    targetUrl: String,
    streamIntervalMs: Long,
    isStreaming: Boolean,
    successCount: Int,
    errorCount: Int,
    lastPayload: String,
    onUrlChange: (String) -> Unit,
    onIntervalChange: (Long) -> Unit,
    onToggleStream: () -> Unit,
    onClearStats: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF49454F))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Remote Destination Settings",
                color = Color(0xFFE6E1E5),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            // URL input path textfield
            OutlinedTextField(
                value = targetUrl,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("target_url_input"),
                label = { Text("Target POST Server URL") },
                placeholder = { Text("http://192.168.1.5:8000/api") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFD0BCFF),
                    unfocusedBorderColor = Color(0xFF49454F),
                    focusedLabelColor = Color(0xFFD0BCFF),
                    unfocusedLabelColor = Color(0xFFCAC4D0),
                    focusedTextColor = Color(0xFFE6E1E5),
                    unfocusedTextColor = Color(0xFFE6E1E5),
                    focusedContainerColor = Color(0xFF1C1B1F),
                    unfocusedContainerColor = Color(0xFF1C1B1F)
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { /* Handle keyboard dismiss */ }),
                enabled = !isStreaming
            )

            // Streaming Interval Slider / Picker
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Post Frequency Interval", color = Color(0xFFCAC4D0), fontSize = 12.sp)
                    Text(
                        text = "${streamIntervalMs} ms (${"%.1f".format(1000f / streamIntervalMs)} Hz)",
                        color = Color(0xFFD0BCFF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = streamIntervalMs.toFloat(),
                    onValueChange = { onIntervalChange(it.toLong().coerceIn(100, 5000)) },
                    valueRange = 100f..5000f,
                    steps = 48,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFD0BCFF),
                        activeTrackColor = Color(0xFFD0BCFF),
                        inactiveTrackColor = Color(0xFF49454F)
                    ),
                    enabled = !isStreaming
                )
            }

            // Quick select interval row
            if (!isStreaming) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val intervals = listOf(
                        200L to "Fast",
                        500L to "Medium",
                        1000L to "Standard",
                        3000L to "Eco"
                    )
                    intervals.forEach { (ms, name) ->
                        val isSelected = streamIntervalMs == ms
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFFD0BCFF).copy(alpha = 0.15f) else Color(0xFF1C1B1F))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFFD0BCFF) else Color(0xFF49454F),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onIntervalChange(ms) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = name,
                                color = if (isSelected) Color.White else Color(0xFFCAC4D0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Client Statistics telemetry counters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1B1F))
                    .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "STREAM COUNTERS", color = Color(0xFFCAC4D0), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Success: $successCount",
                            color = Color(0xFF7DFFB3),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Error: $errorCount",
                            color = Color(0xFFB3261E),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Button(
                    onClick = onClearStats,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2930)),
                    border = BorderStroke(1.dp, Color(0xFF49454F)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Clear", color = Color(0xFFE6E1E5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Big Start/Stop button with Elegant Dark Theme colors
            Button(
                onClick = onToggleStream,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("stream_toggle_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) Color(0xFFB3261E) else Color(0xFFD0BCFF),
                    contentColor = if (isStreaming) Color.White else Color(0xFF381E72)
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = if (isStreaming) "Stop Streaming" else "Start Streaming"
                    )
                    Text(
                        text = if (isStreaming) "STOP DATA STREAMING" else "START IMU STREAM (POST)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun WebserverControlCard(
    isServerRunning: Boolean,
    getIps: () -> List<String>,
    onToggleServer: () -> Unit,
    onCopyUrl: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF49454F))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Embedded Webserver Core",
                        color = Color(0xFFE6E1E5),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Host a JSON telemetry feed directly on your Local Area Network",
                        color = Color(0xFFCAC4D0),
                        fontSize = 11.sp
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF49454F), thickness = 0.5.dp)

            // IP addresses and routes list
            if (isServerRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ACTIVE ACCESS ENDPOINTS (Tap to Copy):",
                        color = Color(0xFFD0BCFF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    val ips = getIps()
                    ips.forEach { ip ->
                        val url = "http://$ip:8080/sensor-data"
                        val postUrl = "http://$ip:8080/post-data"
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
                                .clickable { onCopyUrl(url) }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "endpoint icon", tint = Color(0xFFD0BCFF), modifier = Modifier.size(14.dp))
                                    Text(
                                        text = "GET API Route",
                                        color = Color(0xFFCAC4D0),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE8DEF8))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "COPY",
                                        color = Color(0xFF1D192B),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = url,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            HorizontalDivider(color = Color(0xFF49454F), thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(4.dp))

                            Row {
                                Text(
                                    text = "POST Raw Payload (to test received text display screen): ",
                                    color = Color(0xFFCAC4D0),
                                    fontSize = 9.sp
                                )
                            }
                            Text(
                                text = postUrl,
                                color = Color(0xFFD0BCFF),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1C1B1F))
                        .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Server offline",
                            tint = Color(0xFFCAC4D0),
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Embedded server is offline",
                            color = Color(0xFFE6E1E5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Start the server to make the IMU JSON routes visible over Wi-Fi.",
                            color = Color(0xFFCAC4D0),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Start/Stop server button
            Button(
                onClick = onToggleServer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("server_toggle_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServerRunning) Color(0xFFB3261E) else Color(0xFFD0BCFF),
                    contentColor = if (isServerRunning) Color.White else Color(0xFF381E72)
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isServerRunning) Icons.Default.Close else Icons.Default.Refresh,
                        contentDescription = "Toggle Webserver"
                    )
                    Text(
                        text = if (isServerRunning) "STOP WEBSERVER" else "START WEBSERVER ENGINE (Port 8080)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun DataViewCard(
    title: String,
    jsonContent: String,
    badgeColor: Color,
    badgeText: String,
    infoText: String,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF49454F))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color(0xFFE6E1E5),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .border(1.dp, badgeColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Text(
                text = infoText,
                color = Color(0xFFCAC4D0),
                fontSize = 11.sp
            )

            // Monospaced Code Text Container (Terminals styled matching the theme definition)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1B1F))
                    .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = jsonContent,
                    color = Color(0xFF7DFFB3), // Glowing text color
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun TerminalLogCard(
    title: String,
    logs: List<String>,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF49454F))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = Color(0xFFE6E1E5),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF49454F))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "CONSOLE LOGS",
                        color = Color(0xFFE6E1E5),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1B1F))
                    .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Initializing terminal logs...\nWaiting for activity...",
                        color = Color(0xFFCAC4D0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else {
                    Column {
                        logs.forEach { log ->
                            Text(
                                text = log,
                                color = if (log.contains("Success", ignoreCase = true) || log.contains("started", ignoreCase = true)) {
                                    Color(0xFF7DFFB3)
                                } else if (log.contains("Fail", ignoreCase = true) || log.contains("error", ignoreCase = true)) {
                                    Color(0xFFB3261E)
                                } else {
                                    Color(0xFFE6E1E5)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
