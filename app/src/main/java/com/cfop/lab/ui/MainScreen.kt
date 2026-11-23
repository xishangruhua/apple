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
import java.util.concurrent.Executors

// Apple Liquid Glass 单色光学配色令牌
private val InkPrimary = Color(0xFF18181B)
private val InkSecondary = Color(0xFF52525B)
private val GlassBg = Color(0xDCF2F3F7)
private val GlassCardBg = Color(0xEBFFFFFF)
private val GlassBorder = Color(0x3318181B)
private val GlassHighlight = Color(0x99FFFFFF)

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

    // 公式播放状态
    var activeFormula by remember { mutableStateOf(FormulaRepository.allFormulas.first { it.id == "oll-27" }) }
    var tokens by remember { mutableStateOf(activeFormula.alg.split(" ").filter { it.isNotBlank() }) }
    var currentStep by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var showTips by remember { mutableStateOf(false) }
    var speedIdx by remember { mutableIntStateOf(1) }
    val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    // 浮层控制状态
    var showLibrarySheet by remember { mutableStateOf(false) }
    var showOrbSheet by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var focusMask by remember { mutableStateOf(true) }
    val masteredIds = remember { mutableStateListOf<String>() }

    // 首次载入默认小鱼公式起手态
    LaunchedEffect(Unit) {
        cubeState.activeStage = activeFormula.stage
        cubeState.focusMask = focusMask
        cubeState.setupFormulaAtStep(tokens, 0)
    }

    fun selectFormula(item: FormulaItem) {
        isPlaying = false
        activeFormula = item
        tokens = item.alg.trim().split(Regex("\s+")).filter { it.isNotBlank() }
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

    // 自动演示协程循环
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

    // 打开原生扫码时暂停 OpenGL 线程，关闭后恢复，确保零 GPU 抢占
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

        // 2) 顶部三段式零重叠液态玻璃控制栏（适配 3200x1440 全面屏）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左上：公式库与 3D 沙盒按钮
            GlassIconButton(
                onClick = { showLibrarySheet = true },
                icon = Icons.Rounded.MenuBook,
                contentDesc = "公式库"
            )

            // 中上：缩放 / 复位 / 原生 OpenCV 扫码胶囊
            Row(
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x40000000))
                    .clip(RoundedCornerShape(999.dp))
                    .background(GlassBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                        .clickable { showScanner = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = "扫码", tint = InkPrimary, modifier = Modifier.size(16.dp))
                    Text("扫码", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkPrimary)
                }
            }

            // 右上：悬浮球控制台按钮（WCA 打乱 / 计时器 / 焦点蒙版）
            GlassIconButton(
                onClick = { showOrbSheet = true },
                icon = Icons.Rounded.Tune,
                contentDesc = "控制台"
            )
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
            // 标题行
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

            // 可点击跳步公式令牌轨
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

            // 播放传输控制行
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

                // 倍速按钮
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

        // 4) 公式库与 3D 沙盒原生浮层
        if (showLibrarySheet) {
            LibraryAndSandboxOverlay(
                masteredIds = masteredIds,
                activeId = activeFormula.id,
                onSelectFormula = {
                    selectFormula(it)
                    showLibrarySheet = false
                },
                onToggleMaster = { id ->
                    if (masteredIds.contains(id)) masteredIds.remove(id) else masteredIds.add(id)
                },
                onSandboxMove = { mv ->
                    isPlaying = false
                    cubeState.enqueueMove(mv)
                },
                onClose = { showLibrarySheet = false }
            )
        }

        // 5) 控制台与内置 WCA 计时器原生浮层
        if (showOrbSheet) {
            OrbConsoleOverlay(
                focusMask = focusMask,
                onToggleMask = {
                    focusMask = it
                    cubeState.focusMask = it
                },
                onScramble = {
                    val seq = FormulaRepository.generateWcaScramble(20)
                    val inv = Cube3DState.invertAlgorithm(seq)
                    selectFormula(
                        FormulaItem(
                            id = "wca-scramble",
                            stage = CfopStage.PLL,
                            category = "打乱复原",
                            code = "WCA复原",
                            name = "20 步随机打乱复原步骤",
                            alg = inv.joinToString(" "),
                            is2Look = false,
                            tips = "已对 3D 魔方施加标准 20 步 WCA 打乱，点击下方步骤或播放键即可演示完整复原。"
                        )
                    )
                    showOrbSheet = false
                },
                onResetSolved = {
                    isPlaying = false
                    cubeState.resetSolved()
                    currentStep = tokens.size
                    showOrbSheet = false
                },
                onClose = { showOrbSheet = false }
            )
        }

        // 6) 原生 CameraX + OpenCV 6 面寻方拍照求解页
        if (showScanner) {
            NativeCameraScannerOverlay(
                onClose = { showScanner = false },
                onSolved = { moves ->
                    haptic(20L)
                    showScanner = false
                    showTips = true
                    selectFormula(
                        FormulaItem(
                            id = "native-min2phase",
                            stage = CfopStage.PLL,
                            category = "扫码求解",
                            code = "原生求解",
                            name = "OpenCV + min2phase 最优解",
                            alg = moves,
                            is2Look = false,
                            tips = "由原生 CameraX + OpenCV 寻方裁剪、CIEDE2000 校色与 min2phase 两阶段算法求得的最优复原步骤。"
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun GlassIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, contentDesc: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(10.dp, CircleShape, ambientColor = Color(0x44000000))
            .clip(CircleShape)
            .background(GlassBg)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDesc, tint = InkPrimary, modifier = Modifier.size(20.dp))
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
private fun LibraryAndSandboxOverlay(
    masteredIds: List<String>,
    activeId: String,
    onSelectFormula: (FormulaItem) -> Unit,
    onToggleMaster: (String) -> Unit,
    onSandboxMove: (String) -> Unit,
    onClose: () -> Unit
) {
    var mode by remember { mutableIntStateOf(0) } // 0=公式库, 1=3D沙盒
    var stage by remember { mutableStateOf(CfopStage.OLL) }
    var only2Look by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(stage, only2Look, query) {
        FormulaRepository.allFormulas.filter {
            it.stage == stage &&
            (!only2Look || it.is2Look) &&
            (query.isBlank() || it.code.contains(query, true) || it.name.contains(query, true))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x38000000))
            .clickable(onClick = onClose)
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF4F2F3F7))
                .border(1.dp, GlassBorder, RoundedCornerShape(26.dp))
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("CFOP 速拧实验室 · 纯原生 v3.0", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = InkPrimary)
                    Text("已掌握 ${masteredIds.size} / ${FormulaRepository.allFormulas.size} 公式", fontSize = 11.sp, color = InkSecondary)
                }
                SmallGlassAction(Icons.Rounded.Close, "关闭", onClose)
            }

            // 模式切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x16000000))
                    .padding(3.dp)
            ) {
                listOf("完整公式库 (69)", "3D 转动沙盒").forEachIndexed { idx, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (mode == idx) Color.White else Color.Transparent)
                            .clickable { mode = idx }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (mode == idx) InkPrimary else InkSecondary)
                    }
                }
            }

            if (mode == 0) {
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
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        Text("2-Look", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (only2Look) Color.White else InkPrimary)
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { item ->
                        val isDone = masteredIds.contains(item.id)
                        val isSel = item.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSel) Color.White else GlassCardBg)
                                .border(if (isSel) 1.5.dp else 1.dp, if (isSel) InkPrimary else GlassBorder, RoundedCornerShape(14.dp))
                                .clickable { onSelectFormula(item) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.code, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = InkPrimary)
                                    if (item.is2Look) {
                                        Text("2-Look", fontSize = 10.sp, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(InkPrimary).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                    Text(item.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = InkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(item.alg, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = InkSecondary)
                            }
                            IconButton(onClick = { onToggleMaster(item.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "掌握",
                                    tint = if (isDone) Color(0xFF059669) else Color(0xFFCBD5E1),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // 3D 沙盒单步转动按钮网格
                val moves = listOf(
                    listOf("R", "R'", "R2", "L", "L'", "L2"),
                    listOf("U", "U'", "U2", "D", "D'", "D2"),
                    listOf("F", "F'", "F2", "B", "B'", "B2"),
                    listOf("r", "r'", "M", "M'", "M2", "y")
                )
                Text("点击任意转动指令，后台 3D OpenGL 魔方实时执行磁吸旋转：", fontSize = 12.sp, color = InkSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    moves.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { mv ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
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
        }
    }
}

@Composable
private fun OrbConsoleOverlay(
    focusMask: Boolean,
    onToggleMask: (Boolean) -> Unit,
    onScramble: () -> Unit,
    onResetSolved: () -> Unit,
    onClose: () -> Unit
) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x38000000))
            .clickable(onClick = onClose)
            .padding(16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF4F2F3F7))
                .border(1.dp, GlassBorder, RoundedCornerShape(26.dp))
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("速拧控制台 · WCA 计时器", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = InkPrimary)
                SmallGlassAction(Icons.Rounded.Close, "关闭", onClose)
            }

            // 对称 2x2 控制台
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onScramble,
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("WCA 打乱并求解", fontSize = 12.sp, color = Color.White)
                }
                OutlinedButton(
                    onClick = onResetSolved,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(12.dp)
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

            // 内置 WCA 计时器卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassCardBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(scrambleText, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = InkSecondary)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
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
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = String.format("%.2f", elapsedMs / 1000.0),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = if (running) Color(0xFF059669) else InkPrimary
                        )
                        Text(if (running) "点击停止计时" else "点击开始 WCA 计时", fontSize = 11.sp, color = InkSecondary)
                    }
                }
                val best = history.minOrNull()?.let { String.format("%.2fs", it) } ?: "--"
                val ao5 = if (history.size >= 5) {
                    val s = history.take(5).sorted()
                    String.format("%.2fs", (s[1] + s[2] + s[3]) / 3.0)
                } else "--"
                Text("Best: $best   |   Ao5: $ao5   |   次数: ${history.size}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = InkPrimary)
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
