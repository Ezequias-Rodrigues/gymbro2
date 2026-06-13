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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
    // Local calibration states to permit manual centering based on user phone posture
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var offsetZBy98 by remember { mutableStateOf(0f) } // normalized around gravity (9.8 m/s2)

    // Interactive rendering avatar selection styles
    var selectedStyle by remember { mutableStateOf("Cyber Neon") }

    // Calibrated physical readings
    val calValueX = imuData.accelX - offsetX
    val calValueY = imuData.accelY - offsetY
    val calValueZ = imuData.accelZ - offsetZBy98 // centered around gravity

    // Smooth physics animated states using damping springs to prevent jitter
    val smoothAccelX by animateFloatAsState(
        targetValue = calValueX,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "smooth_acc_x"
    )
    val smoothAccelY by animateFloatAsState(
        targetValue = calValueY,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "smooth_acc_y"
    )
    val smoothAccelZ by animateFloatAsState(
        targetValue = calValueZ,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "smooth_acc_z"
    )

    val smoothGyroX by animateFloatAsState(
        targetValue = imuData.gyroX,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "smooth_gyro_x"
    )
    val smoothGyroY by animateFloatAsState(
        targetValue = imuData.gyroY,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "smooth_gyro_y"
    )
    val smoothGyroZ by animateFloatAsState(
        targetValue = imuData.gyroZ,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "smooth_gyro_z"
    )

    // Compute variance from static Earth gravity (magnitude deviation)
    val diffX = calValueX
    val diffY = calValueY
    val diffZ = calValueZ - 9.8f
    val motionMagnitude = kotlin.math.sqrt(diffX * diffX + diffY * diffY + diffZ * diffZ)
    val angularSpeed = kotlin.math.abs(imuData.gyroX) + kotlin.math.abs(imuData.gyroY) + kotlin.math.abs(imuData.gyroZ)

    // Dynamic user motion states logic
    val statusText = when {
        motionMagnitude > 4.5f || angularSpeed > 2.2f -> "RUNNING / SPRINTING ⚡"
        motionMagnitude > 1.6f || angularSpeed > 1.2f -> "WALKING / OSCILLATING 🚶"
        motionMagnitude > 0.5f || angularSpeed > 0.4f -> "GENTLE LEAN / SWAYING 🍃"
        else -> "PERFECT STILLNESS 😴"
    }

    val statusBadgeColor = when {
        statusText.contains("⚡") -> Color(0xFF7DFFB3) // Glowing light emerald
        statusText.contains("🚶") -> Color(0xFFD0BCFF) // Accent violet
        statusText.contains("🍃") -> Color(0xFFE8DEF8) // Soft lavender
        else -> Color(0xFF938F99) // Gray status
    }

    // Walking/Running motion phase cycle animation
    val infiniteTransition = rememberInfiniteTransition(label = "runner_cycle_engine")
    val cyclePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (statusText.contains("⚡")) 420 else 840,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "cycle_phase"
    )

    // Smooth movement influence scorer
    val movementScore = if (statusText.contains("⚡") || statusText.contains("🚶")) 1f else 0f
    val smoothMovementFactor by animateFloatAsState(
        targetValue = movementScore,
        animationSpec = tween(350),
        label = "movement_factor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2B2930))
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- HUD HEADER: Status Indicators and Calibrate Action Button ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MOTION HUMAN CHARACTER LIVE",
                    color = Color(0xFFD0BCFF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusBadgeColor)
                    )
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // High-Utility Centering calibration button
            Button(
                onClick = {
                    offsetX = imuData.accelX
                    offsetY = imuData.accelY
                    offsetZBy98 = imuData.accelZ - 9.8f
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1C1B1F),
                    contentColor = Color(0xFFD0BCFF)
                ),
                border = BorderStroke(1.dp, Color(0xFF49454F)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Zero Center Balance",
                    tint = Color(0xFFD0BCFF),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("ZERO CENTER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        HorizontalDivider(color = Color(0xFF49454F), thickness = 0.5.dp)

        // --- CORE GRID WORKSPACE: Options Panel Left, Central Drawing Box Right ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Options Column
            Column(
                modifier = Modifier
                    .width(105.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "BODY MODELS",
                    color = Color(0xFFCAC4D0),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                val stylesList = listOf("Cyber Neon", "Athletic", "Skeleton")
                stylesList.forEach { style ->
                    val isStyleSelected = selectedStyle == style
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isStyleSelected) Color(0xFFE8DEF8) else Color(0xFF1C1B1F))
                            .border(
                                1.dp,
                                if (isStyleSelected) Color(0xFFD0BCFF) else Color(0xFF49454F),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedStyle = style }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = when (style) {
                                    "Cyber Neon" -> Icons.Default.Face
                                    "Athletic" -> Icons.Default.Favorite
                                    else -> Icons.Default.Build
                                },
                                contentDescription = style,
                                tint = if (isStyleSelected) Color(0xFF1D192B) else Color(0xFFCAC4D0),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = style,
                                color = if (isStyleSelected) Color(0xFF1D192B) else Color(0xFFE6E1E5),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Real-time diagnostics details box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1B1F))
                        .padding(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("LEAN SPEED", color = Color(0xFF938F99), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${"%.1f°/s".format(angularSpeed * 57.295f)}",
                            color = Color(0xFF7DFFB3),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text("IMPACT G", color = Color(0xFF938F99), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${"%.2f G".format(motionMagnitude / 9.81f)}",
                            color = Color(0xFFD0BCFF),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Human Body Visualizer Canvas Frame
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1C1B1F))
                    .border(1.dp, Color(0xFF49454F), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Circular target radar background guides
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val w = size.width
                    val h = size.height
                    
                    val radX = w / 2f
                    val radY = h * 0.45f

                    // Draw static radial guidelines
                    drawCircle(
                        color = Color(0xFFD0BCFF).copy(alpha = 0.04f),
                        radius = 75.dp.toPx(),
                        center = Offset(radX, radY),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFFD0BCFF).copy(alpha = 0.07f),
                        radius = 45.dp.toPx(),
                        center = Offset(radX, radY),
                        style = Stroke(
                            width = 0.5f * 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                        )
                    )
                    
                    drawLine(
                        color = Color(0xFFD0BCFF).copy(alpha = 0.025f),
                        start = Offset(radX, 10.dp.toPx()),
                        end = Offset(radX, h - 10.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFFD0BCFF).copy(alpha = 0.025f),
                        start = Offset(10.dp.toPx(), radY),
                        end = Offset(w - 10.dp.toPx(), radY),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Draw a micro Bubble Level at the bottom to represent lateral physical leans
                    val levelCenterY = h - 22.dp.toPx()
                    val levelCenterX = w / 2f
                    drawCircle(
                        color = Color(0xFFCAC4D0).copy(alpha = 0.15f),
                        radius = 11.dp.toPx(),
                        center = Offset(levelCenterX, levelCenterY),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    
                    val bubbleLimit = 9.dp.toPx()
                    val bOfsX = (-smoothAccelX * 1.1f).coerceIn(-bubbleLimit, bubbleLimit)
                    val bOfsY = (smoothAccelY * 1.1f).coerceIn(-bubbleLimit, bubbleLimit)
                    drawCircle(
                        color = Color(0xFF7DFFB3),
                        radius = 3.dp.toPx(),
                        center = Offset(levelCenterX + bOfsX, levelCenterY + bOfsY)
                    )
                }

                // Custom Mannequin Draw Frame
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val w = size.width
                    val h = size.height

                    // Dimensions definitions
                    val midY = h * 0.44f // Waist position
                    val spineHeight = 35.dp.toPx()
                    val headRadius = 10.dp.toPx()
                    val shoulderWidth = 18.dp.toPx()
                    val armSegLength = 20.dp.toPx()
                    val legSegLength = 26.dp.toPx()
                    val hipWidth = 11.dp.toPx()

                    // Horizontal tilts maps from X axis gravity vector acceleration
                    val chestLeanX = (-smoothAccelX * 3.2f).coerceIn(-40f, 40f) // offset scale
                    // Pitch maps from Y axis gravity vector
                    val chestLeanY = (smoothAccelY * -2.2f).coerceIn(-22f, 22f)

                    // Twisting vector maps from Gyro Z
                    val twistAngle = (smoothGyroZ * 14f).coerceIn(-28f, 28f)

                    // Joints Calculation coordinates
                    val basePelvisX = w / 2f
                    val basePelvisY = midY + 12.dp.toPx()

                    val neckX = w / 2f + chestLeanX
                    val neckY = basePelvisY - spineHeight + chestLeanY

                    // Shoulder joint limits
                    val lShldX = neckX - shoulderWidth + twistAngle * 0.2f
                    val lShldY = neckY + twistAngle * 0.1f
                    val rShldX = neckX + shoulderWidth - twistAngle * 0.2f
                    val rShldY = neckY - twistAngle * 0.1f

                    // Floating Human Head
                    val headX = neckX + (chestLeanX * 0.22f)
                    val headY = neckY - headRadius - 5.dp.toPx()

                    // Hip joints
                    val lHipX = basePelvisX - hipWidth
                    val lHipY = basePelvisY
                    val rHipX = basePelvisX + hipWidth
                    val rHipY = basePelvisY

                    // Motion Factor interpolation LERP
                    val mF = smoothMovementFactor

                    // --- LEFT ARM MOVEMENT GENERATOR ---
                    // Idle sway coordinates
                    val lIdleElboX = lShldX - 4.dp.toPx() + (smoothGyroY * 4f)
                    val lIdleElboY = lShldY + armSegLength
                    val lIdleHandX = lIdleElboX - 2.dp.toPx() + (smoothGyroZ * 7f)
                    val lIdleHandY = lIdleElboY + armSegLength + (smoothGyroX * 3f)

                    // Sprinting oscillation coordinates
                    val lSprintElboX = lShldX - 7.dp.toPx() + (kotlin.math.sin(cyclePhase) * 11.dp.toPx())
                    val lSprintElboY = lShldY + armSegLength * 0.7f + (kotlin.math.cos(cyclePhase) * 5.dp.toPx())
                    val lSprintHandX = lShldX - 14.dp.toPx() + (kotlin.math.sin(cyclePhase + 0.3f) * 14.dp.toPx())
                    val lSprintHandY = lSprintElboY + armSegLength * 0.7f + (kotlin.math.cos(cyclePhase + 0.3f) * 11.dp.toPx())

                    // Final LERPed joints
                    val lElbX = lIdleElboX * (1f - mF) + lSprintElboX * mF
                    val lElbY = lIdleElboY * (1f - mF) + lSprintElboY * mF
                    val lHndX = lIdleHandX * (1f - mF) + lSprintHandX * mF
                    val lHndY = lIdleHandY * (1f - mF) + lSprintHandY * mF

                    // --- RIGHT ARM MOVEMENT GENERATOR ---
                    val rIdleElboX = rShldX + 4.dp.toPx() - (smoothGyroY * 4f)
                    val rIdleElboY = rShldY + armSegLength
                    val rIdleHandX = rIdleElboX + 2.dp.toPx() - (smoothGyroZ * 7f)
                    val rIdleHandY = rIdleElboY + armSegLength - (smoothGyroX * 3f)

                    val rSprintElboX = rShldX + 7.dp.toPx() - (kotlin.math.sin(cyclePhase) * 11.dp.toPx())
                    val rSprintElboY = rShldY + armSegLength * 0.7f - (kotlin.math.cos(cyclePhase) * 5.dp.toPx())
                    val rSprintHandX = rShldX + 14.dp.toPx() - (kotlin.math.sin(cyclePhase + 0.3f) * 14.dp.toPx())
                    val rSprintHandY = rSprintElboY + armSegLength * 0.7f - (kotlin.math.cos(cyclePhase + 0.3f) * 11.dp.toPx())

                    val rElbX = rIdleElboX * (1f - mF) + rSprintElboX * mF
                    val rElbY = rIdleElboY * (1f - mF) + rSprintElboY * mF
                    val rHndX = rIdleHandX * (1f - mF) + rSprintHandX * mF
                    val rHndY = rIdleHandY * (1f - mF) + rSprintHandY * mF

                    // --- LEFT LEG MOVEMENT GENERATOR ---
                    val lIdleKneeX = lHipX - 1.dp.toPx() - (smoothAccelX * 0.7f)
                    val lIdleKneeY = lHipY + legSegLength
                    val lIdleFootX = lIdleKneeX - 1.dp.toPx() - (smoothAccelX * 1.3f)
                    val lIdleFootY = lIdleKneeY + legSegLength + if (smoothAccelY > 0) smoothAccelY * 1.8f else 0f

                    val lSprintKneeX = lHipX + (kotlin.math.cos(cyclePhase) * 9.dp.toPx())
                    val lSprintKneeY = lHipY + legSegLength * 0.78f + (kotlin.math.sin(cyclePhase) * 5.dp.toPx())
                    val lSprintFootX = lHipX + (kotlin.math.cos(cyclePhase + 0.4f) * 13.dp.toPx())
                    val lSprintFootY = lHipY + legSegLength * 1.55f + (kotlin.math.sin(cyclePhase + 0.4f) * 7.dp.toPx())

                    val lKneX = lIdleKneeX * (1f - mF) + lSprintKneeX * mF
                    val lKneY = lIdleKneeY * (1f - mF) + lSprintKneeY * mF
                    val lFotX = lIdleFootX * (1f - mF) + lSprintFootX * mF
                    val lFotY = lIdleFootY * (1f - mF) + lSprintFootY * mF

                    // --- RIGHT LEG MOVEMENT GENERATOR ---
                    val rIdleKneeX = rHipX + 1.dp.toPx() - (smoothAccelX * 0.7f)
                    val rIdleKneeY = rHipY + legSegLength
                    val rIdleFootX = rIdleKneeX + 1.dp.toPx() - (smoothAccelX * 1.3f)
                    val rIdleFootY = rIdleKneeY + legSegLength + if (smoothAccelY > 0) smoothAccelY * 1.8f else 0f

                    val rSprintKneeX = rHipX - (kotlin.math.cos(cyclePhase) * 9.dp.toPx())
                    val rSprintKneeY = rHipY + legSegLength * 0.78f - (kotlin.math.sin(cyclePhase) * 5.dp.toPx())
                    val rSprintFootX = rHipX - (kotlin.math.cos(cyclePhase + 0.4f) * 13.dp.toPx())
                    val rSprintFootY = rHipY + legSegLength * 1.55f - (kotlin.math.sin(cyclePhase + 0.4f) * 7.dp.toPx())

                    val rKneX = rIdleKneeX * (1f - mF) + rSprintKneeX * mF
                    val rKneY = rIdleKneeY * (1f - mF) + rSprintKneeY * mF
                    val rFotX = rIdleFootX * (1f - mF) + rSprintFootX * mF
                    val rFotY = rIdleFootY * (1f - mF) + rSprintFootY * mF

                    // Draw joints and limbs based on interactive design choices
                    when (selectedStyle) {
                        "Cyber Neon" -> {
                            val neonPurple = Brush.linearGradient(listOf(Color(0xFFD0BCFF), Color(0xFF381E72)))
                            val neonGreen = Brush.linearGradient(listOf(Color(0xFF7DFFB3), Color(0xFF0F766E)))
                            val mainPurple = Color(0xFFD0BCFF)

                            // Segmented micro robo vertebral columns
                            val steps = 4
                            for (i in 0..steps) {
                                val t = i.toFloat() / steps
                                val px = basePelvisX + (neckX - basePelvisX) * t
                                val py = basePelvisY + (neckY - basePelvisY) * t
                                drawCircle(
                                    color = mainPurple.copy(alpha = 0.82f),
                                    radius = (3.8f - t * 1.3f) * 1.5f * 1.dp.toPx(),
                                    center = Offset(px, py)
                                )
                            }
                            drawLine(Color(0xFFD0BCFF).copy(alpha = 0.45f), Offset(basePelvisX, basePelvisY), Offset(neckX, neckY), strokeWidth = 2.8f * 1.dp.toPx())

                            // Clavicle layout
                            drawLine(Color(0xFFD0BCFF).copy(alpha = 0.65f), Offset(lShldX, lShldY), Offset(rShldX, rShldY), strokeWidth = 4.5f * 1.dp.toPx())
                            drawLine(Color(0xFF49454F), Offset(lHipX, lHipY), Offset(rHipX, rHipY), strokeWidth = 3.5f * 1.dp.toPx())

                            // Left Arm
                            drawLine(neonPurple, Offset(lShldX, lShldY), Offset(lElbX, lElbY), strokeWidth = 3f * 1.dp.toPx())
                            drawLine(neonPurple, Offset(lElbX, lElbY), Offset(lHndX, lHndY), strokeWidth = 2.3f * 1.dp.toPx())
                            drawCircle(Color(0xFF7DFFB3), 3.2f * 1.dp.toPx(), Offset(lElbX, lElbY))
                            drawCircle(Color(0xFFD0BCFF), 2.8f * 1.dp.toPx(), Offset(lHndX, lHndY))

                            // Right Arm
                            drawLine(neonPurple, Offset(rShldX, rShldY), Offset(rElbX, rElbY), strokeWidth = 3f * 1.dp.toPx())
                            drawLine(neonPurple, Offset(rElbX, rElbY), Offset(rHndX, rHndY), strokeWidth = 2.3f * 1.dp.toPx())
                            drawCircle(Color(0xFF7DFFB3), 3.2f * 1.dp.toPx(), Offset(rElbX, rElbY))
                            drawCircle(Color(0xFFD0BCFF), 2.8f * 1.dp.toPx(), Offset(rHndX, rHndY))

                            // Left Leg
                            drawLine(neonGreen, Offset(lHipX, lHipY), Offset(lKneX, lKneY), strokeWidth = 3.8f * 1.dp.toPx())
                            drawLine(neonGreen, Offset(lKneX, lKneY), Offset(lFotX, lFotY), strokeWidth = 3f * 1.dp.toPx())
                            drawCircle(Color(0xFFD0BCFF), 3.8f * 1.dp.toPx(), Offset(lKneX, lKneY))
                            drawCircle(Color(0xFF7DFFB3), 3.2f * 1.dp.toPx(), Offset(lFotX, lFotY))

                            // Right Leg
                            drawLine(neonGreen, Offset(rHipX, rHipY), Offset(rKneX, rKneY), strokeWidth = 3.8f * 1.dp.toPx())
                            drawLine(neonGreen, Offset(rKneX, rKneY), Offset(rFotX, rFotY), strokeWidth = 3f * 1.dp.toPx())
                            drawCircle(Color(0xFFD0BCFF), 3.8f * 1.dp.toPx(), Offset(rKneX, rKneY))
                            drawCircle(Color(0xFF7DFFB3), 3.2f * 1.dp.toPx(), Offset(rFotX, rFotY))

                            // Custom glowing visor head
                            drawCircle(Color(0xFF1C1B1F), headRadius, Offset(headX, headY))
                            drawCircle(Color(0xFFD0BCFF), headRadius, Offset(headX, headY), style = Stroke(width = 2f * 1.dp.toPx()))
                            drawRoundRect(
                                color = Color(0xFF7DFFB3),
                                topLeft = Offset(headX - headRadius * 0.7f, headY - 2.8f * 1.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(headRadius * 1.4f, 4.5f * 1.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f * 1.dp.toPx())
                            )
                        }

                        "Athletic" -> {
                            val activeOrange = Color(0xFFFF8F00)
                            val coreGray = Color(0xFF49454F)
                            val corePulseHeart = Color(0xFFFF5252)

                            // Head details
                            drawCircle(activeOrange, headRadius * 0.9f, Offset(headX, headY))
                            drawCircle(Color.White, headRadius * 0.35f, Offset(headX + (chestLeanX * 0.1f), headY))

                            // Active Heart sensor glow
                            val scale = 1.0f + 0.12f * kotlin.math.sin(cyclePhase * 2f)
                            drawCircle(
                                color = corePulseHeart.copy(alpha = 0.22f),
                                radius = 9f * 1.dp.toPx() * scale,
                                center = Offset(neckX, neckY + 11.dp.toPx())
                            )
                            drawCircle(
                                color = corePulseHeart,
                                radius = 3.5f * 1.dp.toPx() * scale,
                                center = Offset(neckX, neckY + 11.dp.toPx())
                            )

                            // Vertebral rod
                            drawLine(coreGray, Offset(basePelvisX, basePelvisY), Offset(neckX, neckY), strokeWidth = 6f * 1.dp.toPx())

                            // Arms
                            drawLine(activeOrange, Offset(lShldX, lShldY), Offset(lElbX, lElbY), strokeWidth = 3.8f * 1.dp.toPx())
                            drawLine(activeOrange, Offset(lElbX, lElbY), Offset(lHndX, lHndY), strokeWidth = 2.8f * 1.dp.toPx())
                            drawLine(activeOrange, Offset(rShldX, rShldY), Offset(rElbX, rElbY), strokeWidth = 3.8f * 1.dp.toPx())
                            drawLine(activeOrange, Offset(rElbX, rElbY), Offset(rHndX, rHndY), strokeWidth = 2.8f * 1.dp.toPx())

                            // Legs
                            drawLine(coreGray, Offset(lHipX, lHipY), Offset(lKneX, lKneY), strokeWidth = 4.8f * 1.dp.toPx())
                            drawLine(coreGray, Offset(lKneX, lKneY), Offset(lFotX, lFotY), strokeWidth = 3.8f * 1.dp.toPx())
                            drawLine(coreGray, Offset(rHipX, rHipY), Offset(rKneX, rKneY), strokeWidth = 4.8f * 1.dp.toPx())
                            drawLine(coreGray, Offset(rKneX, rKneY), Offset(rFotX, rFotY), strokeWidth = 3.8f * 1.dp.toPx())

                            // Small Shoes at feet positions
                            drawRect(
                                color = activeOrange,
                                topLeft = Offset(lFotX - 4.5f * 1.dp.toPx(), lFotY - 1f * 1.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(9f * 1.dp.toPx(), 3.5f * 1.dp.toPx())
                            )
                            drawRect(
                                color = activeOrange,
                                topLeft = Offset(rFotX - 4.5f * 1.dp.toPx(), rFotY - 1f * 1.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(9f * 1.dp.toPx(), 3.5f * 1.dp.toPx())
                            )
                        }

                        else -> { // Minimalist Skeleton wireframe
                            val lineOfMeshColor = Color(0xFFE6E1E5)

                            drawLine(lineOfMeshColor, Offset(basePelvisX, basePelvisY), Offset(neckX, neckY), strokeWidth = 1.29f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(lShldX, lShldY), Offset(rShldX, rShldY), strokeWidth = 1.25f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(lHipX, lHipY), Offset(rHipX, rHipY), strokeWidth = 1.25f * 1.dp.toPx())

                            drawLine(lineOfMeshColor, Offset(lShldX, lShldY), Offset(lElbX, lElbY), strokeWidth = 1.1f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(lElbX, lElbY), Offset(lHndX, lHndY), strokeWidth = 0.95f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(rShldX, rShldY), Offset(rElbX, rElbY), strokeWidth = 1.1f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(rElbX, rElbY), Offset(rHndX, rHndY), strokeWidth = 0.95f * 1.dp.toPx())

                            drawLine(lineOfMeshColor, Offset(lHipX, lHipY), Offset(lKneX, lKneY), strokeWidth = 1.1f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(lKneX, lKneY), Offset(lFotX, lFotY), strokeWidth = 0.95f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(rHipX, rHipY), Offset(rKneX, rKneY), strokeWidth = 1.1f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(rKneX, rKneY), Offset(rFotX, rFotY), strokeWidth = 0.95f * 1.dp.toPx())

                            // Wireframe crosshead circle
                            drawCircle(lineOfMeshColor, headRadius * 0.8f, Offset(headX, headY), style = Stroke(width = 0.95f * 1.dp.toPx()))
                            drawLine(lineOfMeshColor, Offset(headX, headY - headRadius*0.8f), Offset(headX, headY + headRadius*0.8f), strokeWidth = 0.5f * 1.dp.toPx())
                            drawLine(lineOfMeshColor, Offset(headX - headRadius*0.8f, headY), Offset(headX + headRadius*0.8f, headY), strokeWidth = 0.5f * 1.dp.toPx())
                        }
                    }
                }
            }
        }

        // Sub footer text diagnostic status tracker
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1B1F))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SYSTEM STATUS: SECURE TELEMETRY FEEDS LINKED",
                color = Color(0xFFCAC4D0),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "DAMPED FILTER: ON",
                color = Color(0xFF7DFFB3),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
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
