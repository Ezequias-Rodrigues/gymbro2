package com.example

import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.sensor.ImuData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
                    containerColor = Color(0xFF0F172A)
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
    jackFactor: Float,
    status: UserStatus,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var offsetZ by remember { mutableStateOf(0f) }

    val calX = imuData.accelX - offsetX
    val calY = imuData.accelY - offsetY

    val sX by animateFloatAsState(targetValue = calX, label = "acc_x")
    val sY by animateFloatAsState(targetValue = calY, label = "acc_y")
    val sGZ by animateFloatAsState(targetValue = imuData.gyroZ, label = "gyro_z")

    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E293B)).border(1.dp, Color(0xFF334155), RoundedCornerShape(24.dp)).padding(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("VISUALIZAÇÃO IMU", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(status.color))
                        Text(status.text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

                    val cLeanX = (-sX * 3.2f).coerceIn(-40f, 40f)
                    val cLeanY = (sY * -2.2f).coerceIn(-22f, 22f)
                    val twist = (sGZ * 14f).coerceIn(-28f, 28f)

                    val pX = midX - (sX * 12f).coerceIn(-50f, 50f)
                    val pY = midY + 12.dp.toPx() + (sY * 12f).coerceIn(-40f, 40f)
                    val nX = midX + cLeanX - (sX * 12f).coerceIn(-50f, 50f)
                    val nY = pY - 35.dp.toPx() + cLeanY

                    val sWidth = 18.dp.toPx()
                    val lSX = nX - sWidth + twist * 0.2f
                    val lSY = nY + twist * 0.1f
                    val rSX = nX + sWidth - twist * 0.2f
                    val rSY = nY - twist * 0.1f

                    val boneColor = Color(0xFF3B82F6)
                    val jointColor = Color(0xFF34D399)
                    val limbColor = Color(0xFF64748B)

                    drawLine(boneColor, Offset(pX, pY), Offset(nX, nY), strokeWidth = 5f)
                    drawLine(boneColor, Offset(lSX, lSY), Offset(rSX, rSY), strokeWidth = 5f)
                    drawCircle(boneColor, 12.dp.toPx(), Offset(nX, nY - 15.dp.toPx()), style = Stroke(width = 3f))

                    val aLen = 25.dp.toPx()
                    val lLen = 30.dp.toPx()
                    val aAL = (Math.PI * 0.75 + jackFactor * Math.PI * 0.6).toFloat()
                    val aAR = (Math.PI * 0.25 - jackFactor * Math.PI * 0.6).toFloat()
                    val lEX = lSX + aLen * kotlin.math.cos(aAL)
                    val lEY = lSY + aLen * kotlin.math.sin(aAL)
                    val rEX = rSX + aLen * kotlin.math.cos(aAR)
                    val rEY = rSY + aLen * kotlin.math.sin(aAR)
                    drawLine(limbColor, Offset(lSX, lSY), Offset(lEX, lEY), strokeWidth = 4f)
                    drawLine(limbColor, Offset(rSX, rSY), Offset(rEX, rEY), strokeWidth = 4f)

                    val hWidth = 12.dp.toPx()
                    val lHX = pX - hWidth
                    val rHX = pX + hWidth
                    val lAL = (Math.PI * 0.5 + jackFactor * 0.4).toFloat()
                    val lAR = (Math.PI * 0.5 - jackFactor * 0.4).toFloat()
                    val lFX = lHX + lLen * kotlin.math.cos(lAL)
                    val lFY = pY + lLen * kotlin.math.sin(lAL)
                    val rFX = rHX + lLen * kotlin.math.cos(lAR)
                    val rFY = pY + lLen * kotlin.math.sin(lAR)
                    drawLine(limbColor, Offset(lHX, pY), Offset(lFX, lFY), strokeWidth = 4f)
                    drawLine(limbColor, Offset(rHX, pY), Offset(rFX, rFY), strokeWidth = 4f)

                    drawCircle(jointColor, 5f, Offset(lSX, lSY))
                    drawCircle(jointColor, 5f, Offset(rSX, rSY))
                    drawCircle(jointColor, 5f, Offset(pX, pY))
                    drawCircle(jointColor, 4f, Offset(lEX, lEY))
                    drawCircle(jointColor, 4f, Offset(rEX, rEY))
                    drawCircle(jointColor, 4f, Offset(lFX, lFY))
                    drawCircle(jointColor, 4f, Offset(rFX, rFY))
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
    val imuThreshold by viewModel.imuThreshold.collectAsStateWithLifecycle()
    val userStatus by viewModel.userStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
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

    LaunchedEffect(Unit) {
        viewModel.onRepCounted.collect {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val isJacking = jackResult?.isJacking == true
    val jackFactor = if (isJacking) 1f else 0f

    val syncIconColor by remember {
        derivedStateOf {
            if (isStreaming && errorCount == 0) Color(0xFF34D399)
            else if (isStreaming && errorCount > 0) Color(0xFFFBBF24)
            else Color(0xFF3B82F6)
        }
    }

    val connectionStatusText by remember {
        derivedStateOf {
            if (errorCount > 0) "Problemas na conexão" else "Conectado"
        }
    }

    val connectionStatusColor by remember {
        derivedStateOf {
            if (errorCount > 0) Color(0xFFFBBF24) else Color(0xFF34D399)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().drawBehind {
            drawCircle(brush = Brush.radialGradient(colors = listOf(Color(0xFF3B82F6).copy(alpha = 0.15f), Color.Transparent), center = Offset(size.width * 0.8f, size.height * 0.2f), radius = size.width))
        }.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("GymBro Tracker", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text("Mantenha o foco. Eu cuido das repetições.", color = Color(0xFF94A3B8), fontSize = 16.sp, textAlign = TextAlign.Center)

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(32.dp), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (isJacking) "EXERCITANDO" else "AGUARDANDO", color = if (isJacking) Color(0xFF34D399) else Color(0xFF64748B), fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 12.sp)
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
            OrientationVisualizer(imuData = imuState, jackFactor = jackFactor, status = userStatus, modifier = Modifier.fillMaxWidth().height(300.dp))
        }

        if (selectedMode == DetectionMode.IMU) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.5f)), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFF334155))) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Configuração de Sensibilidade", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Ajuste a força necessária para contar um salto. Aumente se houver contagens falsas.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Slider(
                        value = imuThreshold,
                        onValueChange = { viewModel.updateImuThreshold(it) },
                        valueRange = 10f..30f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF3B82F6), activeTrackColor = Color(0xFF3B82F6))
                    )
                    Text("Sensibilidade: ${"%.1f".format(imuThreshold)}", color = Color(0xFF3B82F6), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.5f)), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFF334155))) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Cloud, contentDescription = null, tint = syncIconColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Sincronizar Treino", color = Color.White, fontWeight = FontWeight.Bold)
                        if (isStreaming) {
                            Text(connectionStatusText, color = connectionStatusColor, fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = isStreaming, onCheckedChange = { viewModel.toggleStreaming() }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF3B82F6)))
                }
                if (!isStreaming) {
                    OutlinedTextField(value = targetUrl, onValueChange = { viewModel.updateTargetUrl(it) }, modifier = Modifier.fillMaxWidth(), label = { Text("URL do Servidor") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF334155), focusedBorderColor = Color(0xFF3B82F6), unfocusedTextColor = Color.White, focusedTextColor = Color.White), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }))
                } else {
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F172A)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("SUCESSO: $successCount", color = Color(0xFF34D399), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("FALHAS: $errorCount", color = if (errorCount > 0) Color(0xFFEF4444) else Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Ocultar Logs" else "Ver Logs", color = Color(0xFF3B82F6), fontSize = 12.sp) }
                    }
                }
                AnimatedVisibility(visible = showLogs && isStreaming) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black).padding(8.dp).verticalScroll(rememberScrollState())) {
                        Text(text = streamLogs.joinToString("\n"), color = Color(0xFF34D399), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}
