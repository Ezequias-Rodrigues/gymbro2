package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.sensor.ImuData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.CameraPreview
import com.example.camera.PoseOverlay
import com.example.ml.DetectionMode
import com.example.ml.JackResult
import com.example.ml.PoseResult
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F172A) // Deep Slate Navy
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
fun OrientationVisualizer(
    imuData: ImuData,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var offsetZ by remember { mutableStateOf(0f) }

    val calValueX = imuData.accelX - offsetX
    val calValueY = imuData.accelY - offsetY
    val calValueZ = imuData.accelZ - offsetZ

    val smoothAccelX by animateFloatAsState(targetValue = calValueX, label = "smooth_acc_x")
    val smoothAccelY by animateFloatAsState(targetValue = calValueY, label = "smooth_acc_y")
    val smoothAccelZ by animateFloatAsState(targetValue = calValueZ, label = "smooth_acc_z")
    val smoothGyroZ by animateFloatAsState(targetValue = imuData.gyroZ, label = "smooth_gyro_z")

    val motionMagnitude = kotlin.math.sqrt(calValueX * calValueX + calValueY * calValueY + calValueZ * calValueZ)
    val angularSpeed = kotlin.math.abs(imuData.gyroX) + kotlin.math.abs(imuData.gyroY) + kotlin.math.abs(imuData.gyroZ)

    var jumpDetectedTime by remember { mutableStateOf(0L) }
    val curTime = System.currentTimeMillis()
    if (motionMagnitude > 3.8f && curTime - jumpDetectedTime > 1500) {
        jumpDetectedTime = curTime
    }

    val timeSinceJump = curTime - jumpDetectedTime
    val isJacking = timeSinceJump < 1200
    val jackFactor = if (isJacking) kotlin.math.sin((timeSinceJump.toFloat() / 1200f) * Math.PI.toFloat()) else 0f

    val statusText = when {
        isJacking -> "SALTANDO: POLICHINELO 🤸"
        motionMagnitude > 4.5f || angularSpeed > 2.2f -> "CORRENDO / ACELERADO ⚡"
        motionMagnitude > 1.2f || angularSpeed > 1.0f -> "ANDANDO / MOVIMENTO 🚶"
        motionMagnitude > 0.3f || angularSpeed > 0.3f -> "INCLINANDO / SUAVE 🍃"
        else -> "REPOUSO ABSOLUTO 😴"
    }

    val statusBadgeColor = when {
        statusText.contains("🤸") -> Color(0xFFFBBF24)
        statusText.contains("⚡") -> Color(0xFF34D399)
        statusText.contains("🚶") -> Color(0xFF3B82F6)
        else -> Color(0xFF475569)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "runner_cycle_engine")
    val cyclePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Restart),
        label = "cycle_phase"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("VISUALIZAÇÃO IMU", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusBadgeColor))
                    Text(statusText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = { offsetX = imuData.accelX; offsetY = imuData.accelY; offsetZ = imuData.accelZ }) {
                Icon(Icons.Default.Refresh, "Calibrar", tint = Color(0xFF3B82F6))
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF0F172A)), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midX = w / 2f
                val midY = h * 0.44f

                val chestLeanX = (-smoothAccelX * 3.2f).coerceIn(-40f, 40f)
                val chestLeanY = (smoothAccelY * -2.2f).coerceIn(-22f, 22f)
                val twistAngle = (smoothGyroZ * 14f).coerceIn(-28f, 28f)

                val pelvisX = midX - (smoothAccelX * 12f).coerceIn(-50f, 50f)
                val pelvisY = midY + 12.dp.toPx() + (smoothAccelY * 12f).coerceIn(-40f, 40f)
                val neckX = midX + chestLeanX - (smoothAccelX * 12f).coerceIn(-50f, 50f)
                val neckY = pelvisY - 35.dp.toPx() + chestLeanY

                val shoulderWidth = 18.dp.toPx()
                val lShldX = neckX - shoulderWidth + twistAngle * 0.2f
                val lShldY = neckY + twistAngle * 0.1f
                val rShldX = neckX + shoulderWidth - twistAngle * 0.2f
                val rShldY = neckY - twistAngle * 0.1f

                val boneColor = Color(0xFF3B82F6)
                val jointColor = Color(0xFF34D399)
                val limbColor = Color(0xFF64748B)

                // Torso
                drawLine(boneColor, Offset(pelvisX, pelvisY), Offset(neckX, neckY), strokeWidth = 5f)
                drawLine(boneColor, Offset(lShldX, lShldY), Offset(rShldX, rShldY), strokeWidth = 5f)
                
                // Head
                drawCircle(boneColor, 12.dp.toPx(), Offset(neckX, neckY - 15.dp.toPx()), style = Stroke(width = 3f))

                // Movement calculation
                val armLen = 25.dp.toPx()
                val legLen = 30.dp.toPx()
                
                // Arms (Responsive to jackFactor)
                val armAngleL = (Math.PI * 0.75 + jackFactor * Math.PI * 0.6).toFloat()
                val armAngleR = (Math.PI * 0.25 - jackFactor * Math.PI * 0.6).toFloat()
                
                val lElbowX = lShldX + armLen * kotlin.math.cos(armAngleL)
                val lElbowY = lShldY + armLen * kotlin.math.sin(armAngleL)
                val rElbowX = rShldX + armLen * kotlin.math.cos(armAngleR)
                val rElbowY = rShldY + armLen * kotlin.math.sin(armAngleR)

                drawLine(limbColor, Offset(lShldX, lShldY), Offset(lElbowX, lElbowY), strokeWidth = 4f)
                drawLine(limbColor, Offset(rShldX, rShldY), Offset(rElbowX, rElbowY), strokeWidth = 4f)

                // Legs (Responsive to jump/jack)
                val hipWidth = 12.dp.toPx()
                val lHipX = pelvisX - hipWidth
                val rHipX = pelvisX + hipWidth
                
                val legAngleL = (Math.PI * 0.5 + jackFactor * 0.4).toFloat()
                val legAngleR = (Math.PI * 0.5 - jackFactor * 0.4).toFloat()
                
                val lFootX = lHipX + legLen * kotlin.math.cos(legAngleL)
                val lFootY = pelvisY + legLen * kotlin.math.sin(legAngleL)
                val rFootX = rHipX + legLen * kotlin.math.cos(legAngleR)
                val rFootY = pelvisY + legLen * kotlin.math.sin(legAngleR)

                drawLine(limbColor, Offset(lHipX, pelvisY), Offset(lFootX, lFootY), strokeWidth = 4f)
                drawLine(limbColor, Offset(rHipX, pelvisY), Offset(rFootX, rFootY), strokeWidth = 4f)

                // Joints
                drawCircle(jointColor, 5f, Offset(lShldX, lShldY))
                drawCircle(jointColor, 5f, Offset(rShldX, rShldY))
                drawCircle(jointColor, 5f, Offset(pelvisX, pelvisY))
                drawCircle(jointColor, 4f, Offset(lElbowX, lElbowY))
                drawCircle(jointColor, 4f, Offset(rElbowX, rElbowY))
                drawCircle(jointColor, 4f, Offset(lFootX, lFootY))
                drawCircle(jointColor, 4f, Offset(rFootX, rFootY))
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val jackResult by viewModel.jackResult.collectAsStateWithLifecycle()
    val poseResult by viewModel.poseResult.collectAsStateWithLifecycle()
    val isCameraActive by viewModel.isCameraActive.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val successCount by viewModel.successCount.collectAsStateWithLifecycle()
    val errorCount by viewModel.errorCount.collectAsStateWithLifecycle()
    val streamLogs by viewModel.streamLogs.collectAsStateWithLifecycle()
    val targetUrl by viewModel.targetUrl.collectAsStateWithLifecycle()
    val imuState by viewModel.imuState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    var showLogs by remember { mutableStateOf(false) }

    val cameraPermission = android.Manifest.permission.CAMERA
    val permissionState = remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionState.value = granted
        if (granted) viewModel.startCamera()
    }

    LaunchedEffect(selectedMode) {
        if (selectedMode == DetectionMode.CAMERA) {
            if (permissionState.value) viewModel.startCamera()
            else permissionLauncher.launch(cameraPermission)
        } else {
            viewModel.stopCamera()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF3B82F6).copy(alpha = 0.15f), Color.Transparent),
                        center = Offset(size.width * 0.8f, size.height * 0.2f),
                        radius = size.width
                    )
                )
            }
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("GymBro Tracker", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text("Mantenha o foco. Eu cuido das repetições.", color = Color(0xFF94A3B8), fontSize = 16.sp, textAlign = TextAlign.Center)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(32.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (jackResult?.isJacking == true) "EXERCITANDO" else "AGUARDANDO", color = if (jackResult?.isJacking == true) Color(0xFF34D399) else Color(0xFF64748B), fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 12.sp)
                Text("${jackResult?.repCount ?: 0}", color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.Black)
                Text("REPETIÇÕES", color = Color(0xFF94A3B8), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF1E293B)).padding(4.dp)) {
            listOf(DetectionMode.IMU to "Sensor", DetectionMode.CAMERA to "Câmera").forEach { (mode, label) ->
                val isSelected = selectedMode == mode
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (isSelected) Color(0xFF3B82F6) else Color.Transparent).clickable { viewModel.setDetectionMode(mode) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (isSelected) Color.White else Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                }
            }
        }

        if (selectedMode == DetectionMode.CAMERA) {
            Box(modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black).border(2.dp, Color(0xFF334155), RoundedCornerShape(24.dp))) {
                CameraPreview(modifier = Modifier.fillMaxSize(), onFrame = { bitmap -> viewModel.onFrameProcessed(viewModel.poseDetector.detect(bitmap)) }, cameraActive = isCameraActive)
                PoseOverlay(poseResult = poseResult, modifier = Modifier.fillMaxSize())
            }
        } else {
            OrientationVisualizer(imuData = imuState, modifier = Modifier.fillMaxWidth().height(300.dp))
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.5f)), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFF334155))) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (isStreaming && errorCount == 0) Color(0xFF34D399) else if (isStreaming && errorCount > 0) Color(0xFFFBBF24) else Color(0xFF3B82F6)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Sincronizar Treino", color = Color.White, fontWeight = FontWeight.Bold)
                        if (isStreaming) {
                            val statusText = if (errorCount > 0) "Problemas na conexão" else "Conectado"
                            val statusColor = if (errorCount > 0) Color(0xFFFBBF24) else Color(0xFF34D399)
                            Text(statusText, color = statusColor, fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = isStreaming, onCheckedChange = { viewModel.toggleStreaming() }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6)))
                }
                
                if (!isStreaming) {
                    OutlinedTextField(value = targetUrl, onValueChange = { viewModel.updateTargetUrl(it) }, modifier = Modifier.fillMaxWidth(), label = { Text("URL do Servidor") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF334155), focusedBorderColor = Color(0xFF3B82F6), unfocusedTextColor = Color.White, focusedTextColor = Color.White), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("SUCESSO: $successCount", color = Color(0xFF34D399), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("FALHAS: $errorCount", color = if (errorCount > 0) Color(0xFFEF4444) else Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        TextButton(onClick = { showLogs = !showLogs }) {
                            Text(if (showLogs) "Ocultar Logs" else "Ver Logs", color = Color(0xFF3B82F6), fontSize = 12.sp)
                        }
                    }
                }

                AnimatedVisibility(visible = showLogs && isStreaming) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = streamLogs.joinToString("\n"),
                            color = Color(0xFF34D399),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}
