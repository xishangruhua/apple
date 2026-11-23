package com.cfop.lab.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix as AndroidMatrix
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cfop.lab.engine.Cube3DState
import com.cfop.lab.engine.CubeGLRenderer
import com.cfop.lab.engine.CubeGLSurfaceView
import com.cfop.lab.model.CfopStage
import com.cfop.lab.model.FormulaItem
import com.cfop.lab.model.FormulaRepository
import com.cfop.lab.solver.CubeSolverBridge
import com.cfop.lab.vision.CubeSquareDetector
import com.cfop.lab.vision.RubiksColorResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

private val InkPrimary = Color(0xFF18181B)
private val InkSecondary = Color(0xFF52525B)
private val GlassBg = Color(0xDCF2F3F7)
private val GlassCardBg = Color(0xEBFFFFFF)
private val GlassBorder = Color(0x3318181B)

private val FACE_ORDER = listOf(
    'F' to "F 绿前", 'R' to "R 红右", 'B' to "B 蓝后",
    'L' to "L 橙左", 'U' to "U 黄顶", 'D' to "D 白底"
)
private val FACE_OFFSET = mapOf('U' to 0, 'R' to 9, 'F' to 18, 'D' to 27, 'L' to 36, 'B' to 45)
private val FACE_COLOR_MAP = mapOf(
    'U' to Color(0xFFFFD500), 'D' to Color(0xFFF8FAFC), 'F' to Color(0xFF009E49),
    'B' to Color(0xFF0046AD), 'R' to Color(0xFFC8102E), 'L' to Color(0xFFFF5800)
)
private val COLOR_CYCLE = listOf('U', 'D', 'F', 'B', 'R', 'L')

@Composable
fun CFOPNativeApp(context: Context) {
    val scope = rememberCoroutineScope()

    fun haptic(ms: Long = 8L) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}
    }

    val cubeState = remember {
        Cube3DState().apply {
            onMoveStartHaptic = { haptic(7L) }
        }
    }
    val glRenderer = remember { CubeGLRenderer(cubeState) }
    var glViewRef by remember { mutableStateOf<CubeGLSurfaceView?>(null) }

    var activeFormula by remember { mutableStateOf(FormulaRepository.allFormulas.first { it.id == "oll-27" }) }
    var tokens by remember { mutableStateOf(activeFormula.alg.split(' ').filter { it.isNotBlank() }) }
    var currentStep by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var showTips by remember { mutableStateOf(false) }
    var speedIdx by remember { mutableIntStateOf(1) }
    val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    // 合并后的右手统一控制台浮层 + 原生扫码页
    var showRightConsole by remember { mutableStateOf(false) }
    var consoleTab by remember { mutableIntStateOf(0) } // 0=公式库, 1=3D沙盒, 2=控制与计时
    var showScanner by remember { mutableStateOf(false) }
    var focusMask by remember { mutableStateOf(true) }
    val masteredIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        cubeState.activeStage = activeFormula.stage
        cubeState.focusMask = focusMask
        cubeState.setupFormulaAtStep(tokens, 0)
    }

    fun selectFormula(item: FormulaItem) {
        isPlaying = false
        activeFormula = item
        tokens = item.alg.trim().split(' ').filter { it.isNotBlank() }
        currentStep = 0
        cubeState.activeStage = item.stage
        cubeState.focusMask = focusMask
        cubeState.setupFormulaAtStep(tokens, 0)
    }

    fun jumpToStep(target: Int) {
        isPlaying = false
        val clamped = target.coerceIn(0, tokens.size)
        if (clamped == currentStep) return
        if (clamped == currentStep + 1) {
            val mv = tokens[currentStep]
            currentStep = clamped
            cubeState.enqueueMove(mv)
        } else if (clamped == currentStep - 1) {
            val inv = Cube3DState.invertMove(tokens[currentStep - 1])
            currentStep = clamped
            cubeState.enqueueMove(inv)
        } else {
            currentStep = clamped
            cubeState.setupFormulaAtStep(tokens, clamped)
            haptic(10L)
        }
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        if (currentStep >= tokens.size) {
            currentStep = 0
            cubeState.setupFormulaAtStep(tokens, 0)
            delay(280)
        }
        while (isPlaying && currentStep < tokens.size) {
            val mv = tokens[currentStep]
            currentStep++
            cubeState.enqueueMove(mv)
            val waitMs = ((if (mv.endsWith("2")) 380 else 270) / cubeState.speedMultiplier).toLong()
            delay(waitMs)
        }
        isPlaying = false
    }

    LaunchedEffect(showScanner) {
        if (showScanner) glViewRef?.onPause() else glViewRef?.onResume()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFAFBFD), Color(0xFFECEFF5), Color(0xFFDCE1EA))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1) 全屏原生 OpenGL ES 3.0 磁力速拧魔方
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CubeGLSurfaceView(ctx, glRenderer).also { glViewRef = it }
            }
        )

        // 2) 顶部右手人体工学布局：左侧视角/扫码胶囊 + 右上角唯一合并控制台悬浮球
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x40000000))
                    .clip(RoundedCornerShape(999.dp))
                    .background(GlassBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                IconButton(onClick = { glRenderer.zoomByDelta(1.8f) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.ZoomOut, contentDescription = "缩小", tint = InkPrimary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { glRenderer.zoomByDelta(-1.8f) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = "放大", tint = InkPrimary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { glRenderer.resetCamera() }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重置视角", tint = InkPrimary, modifier = Modifier.size(18.dp))
                }
                Box(modifier = Modifier.width(1.dp).height(16.dp).background(GlassBorder))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable {
                            showRightConsole = false
                            showScanner = true
                        }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = "扫码", tint = InkPrimary, modifier = Modifier.size(16.dp))
                    Text("扫码", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkPrimary)
                }
            }

            // 右手合并控制台悬浮球（公式库 + 3D 沙盒 + WCA 控制台全在右侧）
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .shadow(10.dp, CircleShape, ambientColor = Color(0x44000000))
                    .clip(CircleShape)
                    .background(if (showRightConsole) InkPrimary else GlassBg)
                    .border(1.dp, GlassBorder, CircleShape)
                    .clickable { showRightConsole = !showRightConsole },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (showRightConsole) Icons.Rounded.Close else Icons.Rounded.Apps,
                    contentDescription = "打开右手控制台",
                    tint = if (showRightConsole) Color.White else InkPrimary,
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        // 3) 底部可点击跳步的悬浮公式播放控制舱
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .shadow(14.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x50000000))
                .clip(RoundedCornerShape(24.dp))
                .background(GlassBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(InkPrimary)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(activeFormula.code, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        text = activeFormula.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "$currentStep / ${tokens.size}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = InkSecondary,
                    modifier = Modifier.padding(end = 6.dp)
                )
                IconButton(onClick = { showTips = !showTips }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = "手法解析", tint = if (showTips) InkPrimary else InkSecondary, modifier = Modifier.size(18.dp))
                }
            }

            if (showTips) {
                Text(
                    text = activeFormula.tips,
                    fontSize = 11.5.sp,
                    color = InkSecondary,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassCardBg)
                        .padding(8.dp)
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x16000000))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                itemsIndexed(tokens) { idx, tok ->
                    val isCur = idx == currentStep - 1
                    val isDone = idx < currentStep - 1
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isCur -> InkPrimary
                                    isDone -> Color(0x2218181B)
                                    else -> GlassCardBg
                                }
                            )
                            .clickable { jumpToStep(idx + 1) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = tok,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isCur) Color.White else InkPrimary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallGlassAction(Icons.Rounded.FastRewind, "重置") { jumpToStep(0) }
                    SmallGlassAction(Icons.Rounded.SkipPrevious, "上一步") { jumpToStep(currentStep - 1) }
                    Button(
                        onClick = { isPlaying = !isPlaying },
                        colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPlaying) "暂停" else "播放演示", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    SmallGlassAction(Icons.Rounded.SkipNext, "下一步") { jumpToStep(currentStep + 1) }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassCardBg)
                        .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                        .clickable {
                            speedIdx = (speedIdx + 1) % speeds.size
                            cubeState.speedMultiplier = speeds[speedIdx]
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("${speeds[speedIdx]}×", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = InkPrimary)
                }
            }
        }

        // 4) 右手合并控制台（展开后点击任意空白处自动合拢）
        if (showRightConsole) {
            UnifiedRightConsoleOverlay(
                selectedTab = consoleTab,
                onSelectTab = { consoleTab = it },
                masteredIds = masteredIds,
                activeId = activeFormula.id,
                focusMask = focusMask,
                onToggleMask = {
                    focusMask = it
                    cubeState.focusMask = it
                },
                onSelectFormula = {
                    selectFormula(it)
                    showRightConsole = false
                },
                onToggleMaster = { id ->
                    if (masteredIds.contains(id)) masteredIds.remove(id) else masteredIds.add(id)
                },
                onSandboxMove = { mv ->
                    isPlaying = false
                    cubeState.enqueueMove(mv)
                },
                onScrambleOptimal = {
                    showRightConsole = false
                    scope.launch(Dispatchers.Default) {
                        val scramble = FormulaRepository.generateWcaScramble(20)
                        val facelets54 = CubeSolverBridge.simulateScrambleToFacelets(scramble)
                        val solved = CubeSolverBridge.solve(facelets54)
                        val bestAlg = if (solved.ok && solved.moves.isNotBlank()) {
                            solved.moves
                        } else {
                            Cube3DState.invertAlgorithm(scramble).joinToString(" ")
                        }
                        val moveCount = bestAlg.split(' ').count { it.isNotBlank() }
                        withContext(Dispatchers.Main) {
                            showTips = true
                            selectFormula(
                                FormulaItem(
                                    id = "wca-optimal-solve",
                                    stage = CfopStage.PLL,
                                    category = "最优解",
                                    code = "最优解",
                                    name = "WCA 打乱 · min2phase 最简解 ($moveCount 步)",
                                    alg = bestAlg,
                                    is2Look = false,
                                    tips = "已对 3D 魔方施加 20 步 WCA 打乱，并通过 Kociemba 两阶段算法 (min2phase) 实时求出 $moveCount 步最简复原公式。"
                                )
                            )
                        }
                    }
                },
                onResetSolved = {
                    isPlaying = false
                    cubeState.resetSolved()
                    currentStep = tokens.size
                    showRightConsole = false
                },
                onDismiss = { showRightConsole = false }
            )
        }

        // 5) 原生 CameraX + OpenCV 6 面寻方拍照求解页
        if (showScanner) {
            NativeCameraScannerOverlay(
                onClose = { showScanner = false },
                onSolved = { moves ->
                    haptic(20L)
                    showScanner = false
                    showTips = true
                    val cnt = moves.split(' ').count { it.isNotBlank() }
                    selectFormula(
                        FormulaItem(
                            id = "native-min2phase",
                            stage = CfopStage.PLL,
                            category = "扫码求解",
                            code = "原生最优",
                            name = "OpenCV + min2phase 最简解 ($cnt 步)",
                            alg = moves,
                            is2Look = false,
                            tips = "由原生 CameraX + OpenCV 寻方裁剪、CIEDE2000 校色与 min2phase 两阶段算法求得的 $cnt 步最简复原公式。"
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun SmallGlassAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GlassCardBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = InkPrimary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun UnifiedRightConsoleOverlay(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    masteredIds: List<String>,
    activeId: String,
    focusMask: Boolean,
    onToggleMask: (Boolean) -> Unit,
    onSelectFormula: (FormulaItem) -> Unit,
    onToggleMaster: (String) -> Unit,
    onSandboxMove: (String) -> Unit,
    onScrambleOptimal: () -> Unit,
    onResetSolved: () -> Unit,
    onDismiss: () -> Unit
) {
    var stage by remember { mutableStateOf(CfopStage.OLL) }
    var only2Look by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var running by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    val history = remember { mutableStateListOf<Double>() }
    var scrambleText by remember { mutableStateOf(FormulaRepository.generateWcaScramble(20).joinToString(" ")) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        startMs = System.nanoTime() / 1_000_000L
        while (running) {
            elapsedMs = (System.nanoTime() / 1_000_000L) - startMs
            delay(16)
        }
    }

    val filtered = remember(stage, only2Look, query) {
        FormulaRepository.allFormulas.filter {
            it.stage == stage &&
            (!only2Look || it.is2Look) &&
            (query.isBlank() || it.code.contains(query, true) || it.name.contains(query, true))
        }
    }

    // 外层全屏透明遮罩：轻触任意空白区域立即自动合拢！
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x28000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(top = 64.dp, end = 12.dp, bottom = 148.dp, start = 40.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .shadow(18.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x55000000))
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF5F2F3F7))
                .border(1.dp, GlassBorder, RoundedCornerShape(26.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x16000000))
                    .padding(3.dp)
            ) {
                listOf("公式库", "3D 沙盒", "控制与计时").forEachIndexed { idx, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selectedTab == idx) Color.White else Color.Transparent)
                            .clickable { onSelectTab(idx) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedTab == idx) InkPrimary else InkSecondary)
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CfopStage.entries.forEach { st ->
                            val sel = stage == st
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) InkPrimary else GlassCardBg)
                                    .clickable { stage = st }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(st.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (sel) Color.White else InkPrimary)
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索编号或名称...", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (only2Look) InkPrimary else GlassCardBg)
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                .clickable { only2Look = !only2Look }
                                .padding(horizontal = 11.dp, vertical = 14.dp)
                        ) {
                            Text("2-Look", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (only2Look) Color.White else InkPrimary)
                        }
                    }

                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(filtered, key = { it.id }) { item ->
                            val isDone = masteredIds.contains(item.id)
                            val isSel = item.id == activeId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(if (isSel) Color.White else GlassCardBg)
                                    .border(if (isSel) 1.5.dp else 1.dp, if (isSel) InkPrimary else GlassBorder, RoundedCornerShape(13.dp))
                                    .clickable { onSelectFormula(item) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.code, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = InkPrimary)
                                        if (item.is2Look) {
                                            Text("2-Look", fontSize = 9.5.sp, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(InkPrimary).padding(horizontal = 5.dp, vertical = 1.dp))
                                        }
                                        Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(item.alg, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = InkSecondary)
                                }
                                IconButton(onClick = { onToggleMaster(item.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = "掌握",
                                        tint = if (isDone) Color(0xFF059669) else Color(0xFFCBD5E1),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    val moves = listOf(
                        listOf("R", "R'", "R2", "L", "L'", "L2"),
                        listOf("U", "U'", "U2", "D", "D'", "D2"),
                        listOf("F", "F'", "F2", "B", "B'", "B2"),
                        listOf("r", "r'", "M", "M'", "M2", "y")
                    )
                    Text("点按下方指令，背景 3D 魔方实时执行磁吸转动（点空白处可随时收起）：", fontSize = 11.5.sp, color = InkSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        moves.forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { mv ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(GlassCardBg)
                                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                            .clickable { onSandboxMove(mv) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(mv, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = InkPrimary)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onScrambleOptimal,
                            modifier = Modifier.weight(1f).height(40.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("WCA 打乱最优解", fontSize = 12.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = onResetSolved,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("还原魔方", fontSize = 12.sp, color = InkPrimary)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x16000000))
                            .padding(3.dp)
                    ) {
                        listOf(true to "焦点蒙版", false to "全彩模式").forEach { (isFocus, lbl) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (focusMask == isFocus) Color.White else Color.Transparent)
                                    .clickable { onToggleMask(isFocus) }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(lbl, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (focusMask == isFocus) InkPrimary else InkSecondary)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(GlassCardBg)
                            .border(1.dp, GlassBorder, RoundedCornerShape(15.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(scrambleText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = InkSecondary)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (running) Color(0x1A059669) else Color(0x0D000000))
                                .clickable {
                                    if (running) {
                                        running = false
                                        history.add(0, elapsedMs / 1000.0)
                                        scrambleText = FormulaRepository.generateWcaScramble(20).joinToString(" ")
                                    } else {
                                        elapsedMs = 0L
                                        running = true
                                    }
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format(Locale.US, "%.2f", elapsedMs / 1000.0),
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (running) Color(0xFF059669) else InkPrimary
                                )
                                Text(if (running) "点击停止计时" else "点击开始 WCA 计时", fontSize = 11.sp, color = InkSecondary)
                            }
                        }
                        val best = history.minOrNull()?.let { String.format(Locale.US, "%.2fs", it) } ?: "--"
                        val ao5 = if (history.size >= 5) {
                            val s = history.take(5).sorted()
                            String.format(Locale.US, "%.2fs", (s[1] + s[2] + s[3]) / 3.0)
                        } else "--"
                        Text("Best: $best  |  Ao5: $ao5  |  次数: ${history.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = InkPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeCameraScannerOverlay(
    onClose: () -> Unit,
    onSolved: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPerm = it
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }

    var targetIdx by remember { mutableIntStateOf(0) }
    val rawRgbFaces = remember { mutableStateMapOf<Char, List<IntArray>>() }
    val faceColors = remember { mutableStateMapOf<Char, CharArray>() }
    var statusMsg by remember { mutableStateOf("将 F 绿心面对准白框，点「拍此面并裁剪」；识别后可点下方 9 格微调") }
    var isBusy by remember { mutableStateOf(false) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    val curFaceKey = FACE_ORDER[targetIdx].first
    val curFaceName = FACE_ORDER[targetIdx].second
    val curPreviewGrid = faceColors[curFaceKey] ?: CharArray(9) { curFaceKey }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE8ECF2))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("OpenCV 寻方拍照求解 (${rawRgbFaces.size}/6)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkPrimary)
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(999.dp)) {
                Text("返回 3D", fontSize = 12.sp, color = InkPrimary)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF14161B)),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPerm) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            } else {
                Text("请授予相机权限以开启 OpenCV 寻方拍照", color = Color.White, fontSize = 13.sp)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.64f)
                    .aspectRatio(1f)
                    .border(2.dp, Color.White.copy(alpha = 0.88f), RoundedCornerShape(12.dp))
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FACE_ORDER.forEachIndexed { idx, (key, _) ->
                val done = rawRgbFaces.containsKey(key)
                val isCur = idx == targetIdx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(when { isCur -> InkPrimary; done -> Color(0x2818181B); else -> Color.White })
                        .clickable { targetIdx = idx }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (done) "$key✓" else "$key", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isCur) Color.White else InkPrimary)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GlassCardBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (r in 0 until 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (c in 0 until 3) {
                            val idx = r * 3 + c
                            val ch = curPreviewGrid[idx]
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(FACE_COLOR_MAP[ch] ?: Color.Gray)
                                    .border(1.dp, Color(0x4418181B), RoundedCornerShape(5.dp))
                                    .clickable(enabled = idx != 4) {
                                        val copy = curPreviewGrid.copyOf()
                                        copy[idx] = COLOR_CYCLE[(COLOR_CYCLE.indexOf(ch) + 1) % COLOR_CYCLE.size]
                                        faceColors[curFaceKey] = copy
                                    }
                            )
                        }
                    }
                }
            }
            Text(statusMsg, fontSize = 12.sp, color = InkSecondary, modifier = Modifier.weight(1f))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (isBusy || !hasCameraPerm) return@Button
                    isBusy = true
                    imageCapture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                scope.launch(Dispatchers.Default) {
                                    val bmp = safeImageProxyToBitmap(image)
                                    image.close()
                                    val sample = CubeSquareDetector.detectAndSampleFace(bmp)
                                    bmp.recycle()
                                    withContext(Dispatchers.Main) {
                                        rawRgbFaces[curFaceKey] = sample.rgbGrid
                                        faceColors[curFaceKey] = RubiksColorResolver.previewSingleFace(sample.rgbGrid, curFaceKey)
                                        if (rawRgbFaces.size == 6) {
                                            val rgb54 = MutableList(54) { intArrayOf(0, 0, 0) }
                                            for ((k, grid) in rawRgbFaces) {
                                                val off = FACE_OFFSET[k] ?: continue
                                                for (i in 0 until 9) rgb54[off + i] = grid[i]
                                            }
                                            val resolved = RubiksColorResolver.resolve54Facelets(rgb54)
                                            for ((k, off) in FACE_OFFSET) {
                                                faceColors[k] = resolved.sliceArray(off until off + 9)
                                            }
                                            statusMsg = "6 面全部拍齐并完成 CIEDE2000 全局校色！请点右侧「min2phase 求解」"
                                        } else {
                                            val next = FACE_ORDER.indexOfFirst { !rawRgbFaces.containsKey(it.first) }
                                            if (next != -1) targetIdx = next
                                            statusMsg = "已拍 $curFaceName（${if (sample.detectedSquare) "OpenCV 已寻方裁剪" else "中心框裁剪"}，${rawRgbFaces.size}/6）"
                                        }
                                        isBusy = false
                                    }
                                }
                            }
                            override fun onError(exc: ImageCaptureException) {
                                scope.launch(Dispatchers.Main) {
                                    statusMsg = "拍照失败：${exc.message}"
                                    isBusy = false
                                }
                            }
                        }
                    )
                },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (isBusy) "裁剪识别中..." else "拍此面并裁剪", color = Color.White)
            }

            OutlinedButton(
                onClick = {
                    if (faceColors.size < 6) {
                        Toast.makeText(context, "请先拍完 6 个面（当前 ${faceColors.size}/6）", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    scope.launch(Dispatchers.Default) {
                        val state = CharArray(54)
                        for ((k, off) in FACE_OFFSET) {
                            val arr = faceColors[k] ?: CharArray(9) { k }
                            for (i in 0 until 9) state[off + i] = arr[i]
                        }
                        val res = CubeSolverBridge.solve(String(state))
                        withContext(Dispatchers.Main) {
                            if (res.ok) onSolved(res.moves)
                            else statusMsg = res.error
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("min2phase 求解", color = InkPrimary)
            }
        }
    }
}

private fun safeImageProxyToBitmap(image: ImageProxy): Bitmap {
    val raw = image.toBitmap()
    val rot = image.imageInfo.rotationDegrees
    if (rot == 0) return raw
    val m = AndroidMatrix().apply { postRotate(rot.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    raw.recycle()
    return rotated
}
