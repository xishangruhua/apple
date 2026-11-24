package com.cfop.lab.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.cfop.lab.vision.RubiksColorResolver
import com.cfop.lab.vision.YuvIsometricSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

// 校准 Apple Liquid Glass 光学色彩系统
private val InkPrimary = Color(0xFF18181B)
private val InkSecondary = Color(0xFF52525B)
private val GlassShellGradient = Brush.linearGradient(
    colors = listOf(Color(0xD8FFFFFF), Color(0xC2E7E7EB), Color(0xCFE1E3E8))
)
private val GlassBorderBrush = Brush.linearGradient(
    colors = listOf(Color(0xCCFFFFFF), Color(0x3818181B), Color(0x55FFFFFF))
)
private val GlassCardBg = Color(0xE0FFFFFF)
private val GlassGroove = Color(0x16000000)

private val FACE_COLOR_MAP = mapOf(
    'U' to Color(0xFFFFD500), 'D' to Color(0xFFF8FAFC), 'F' to Color(0xFF00B85C),
    'B' to Color(0xFF0052D9), 'R' to Color(0xFFE11D48), 'L' to Color(0xFFFF6B00)
)
private val COLOR_CYCLE = listOf('U', 'D', 'F', 'B', 'R', 'L')
private val FACE_OFFSET = mapOf('U' to 0, 'R' to 9, 'F' to 18, 'D' to 27, 'L' to 36, 'B' to 45)

private fun Modifier.liquidGlassSurface(corner: Dp = 24.dp, elevation: Dp = 14.dp): Modifier =
    this
        .shadow(elevation, RoundedCornerShape(corner), ambientColor = Color(0x58000000), spotColor = Color(0x44000000))
        .clip(RoundedCornerShape(corner))
        .background(GlassShellGradient)
        .border(1.2.dp, GlassBorderBrush, RoundedCornerShape(corner))

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
        Cube3DState().apply { onMoveStartHaptic = { haptic(7L) } }
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

    var showRightConsole by remember { mutableStateOf(false) }
    var consoleTab by remember { mutableIntStateOf(0) }
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
            delay(260)
        }
        while (isPlaying && currentStep < tokens.size) {
            val mv = tokens[currentStep]
            currentStep++
            cubeState.enqueueMove(mv)
            val waitMs = ((if (mv.endsWith("2")) 370 else 260) / cubeState.speedMultiplier).toLong()
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
        // 1) 全屏高质感圆角倒角 OpenGL ES 3.0 魔方
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> CubeGLSurfaceView(ctx, glRenderer).also { glViewRef = it } }
        )

        // 2) 顶部右手液态玻璃栏：左胶囊（缩放/复位/三面连扫） + 右上角唯一合并悬浮球
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .liquidGlassSurface(corner = 999.dp, elevation = 10.dp)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GlassMiniButton(LgIcon.ZOOM_OUT) { glRenderer.zoomByDelta(1.8f) }
                GlassMiniButton(LgIcon.ZOOM_IN) { glRenderer.zoomByDelta(-1.8f) }
                GlassMiniButton(LgIcon.RESET) { glRenderer.resetCamera() }
                Box(modifier = Modifier.width(1.dp).height(16.dp).background(Color(0x2818181B)))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable {
                            showRightConsole = false
                            showScanner = true
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    GlassVectorIcon(LgIcon.CAMERA, InkPrimary, 16.dp)
                    Text("三面连扫", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkPrimary)
                }
            }

            // 右上角就地形变悬浮球（带弹性缩放动画）
            val orbScale by animateFloatAsState(
                targetValue = if (showRightConsole) 0.92f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "orbScale"
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(orbScale)
                    .shadow(12.dp, CircleShape, ambientColor = Color(0x50000000))
                    .clip(CircleShape)
                    .background(if (showRightConsole) Brush.linearGradient(listOf(InkPrimary, Color(0xFF27272A))) else GlassShellGradient)
                    .border(1.2.dp, GlassBorderBrush, CircleShape)
                    .clickable {
                        haptic(8L)
                        showRightConsole = !showRightConsole
                    },
                contentAlignment = Alignment.Center
            ) {
                GlassVectorIcon(
                    icon = if (showRightConsole) LgIcon.CLOSE else LgIcon.CUBE,
                    tint = if (showRightConsole) Color.White else InkPrimary,
                    size = 21.dp
                )
            }
        }

        // 3) 底部悬浮公式播放控制舱（带平滑展开手法解析 & 跳步弹性动画）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .liquidGlassSurface(corner = 26.dp, elevation = 16.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
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
                GlassPillIcon(LgIcon.INFO, active = showTips) { showTips = !showTips }
            }

            AnimatedVisibility(
                visible = showTips,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 420f)) + fadeIn(),
                exit = shrinkVertically(spring(stiffness = 460f)) + fadeOut()
            ) {
                Text(
                    text = activeFormula.tips,
                    fontSize = 11.5.sp,
                    color = InkSecondary,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(GlassCardBg)
                        .padding(9.dp)
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(GlassGroove)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                itemsIndexed(tokens) { idx, tok ->
                    val isCur = idx == currentStep - 1
                    val isDone = idx < currentStep - 1
                    val tokScale by animateFloatAsState(
                        targetValue = if (isCur) 1.07f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                        label = "tokScale"
                    )
                    Box(
                        modifier = Modifier
                            .scale(tokScale)
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
                    GlassPillIcon(LgIcon.REWIND) { jumpToStep(0) }
                    GlassPillIcon(LgIcon.PREV) { jumpToStep(currentStep - 1) }
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(InkPrimary)
                            .clickable { isPlaying = !isPlaying }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassVectorIcon(if (isPlaying) LgIcon.PAUSE else LgIcon.PLAY, Color.White, 13.dp)
                            Text(if (isPlaying) "暂停" else "播放演示", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    GlassPillIcon(LgIcon.NEXT) { jumpToStep(currentStep + 1) }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassCardBg)
                        .border(1.dp, Color(0x2618181B), RoundedCornerShape(10.dp))
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

        // 4) 右手合并液态玻璃控制台（带右上角弹性展开动画 + 点击空白自动合拢）
        AnimatedVisibility(
            visible = showRightConsole,
            enter = fadeIn(tween(180)) + scaleIn(
                initialScale = 0.84f,
                transformOrigin = TransformOrigin(0.95f, 0.05f),
                animationSpec = spring(dampingRatio = 0.74f, stiffness = 420f)
            ),
            exit = fadeOut(tween(150)) + scaleOut(
                targetScale = 0.86f,
                transformOrigin = TransformOrigin(0.95f, 0.05f),
                animationSpec = tween(160)
            )
        ) {
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
                        val cnt = bestAlg.split(' ').count { it.isNotBlank() }
                        withContext(Dispatchers.Main) {
                            showTips = true
                            selectFormula(
                                FormulaItem(
                                    id = "wca-optimal-solve",
                                    stage = CfopStage.PLL,
                                    category = "最优解",
                                    code = "最优解",
                                    name = "WCA 打乱 · 最简解 ($cnt 步)",
                                    alg = bestAlg,
                                    is2Look = false,
                                    tips = "已施加 20 步 WCA 打乱，并通过 min2phase 两阶段算法实时求出 $cnt 步最简复原公式。"
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

        // 5) 免点击三面同步自动连扫页（转一圈 2 次自动采完 6 面！）
        AnimatedVisibility(
            visible = showScanner,
            enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it / 10 }),
            exit = fadeOut(tween(180)) + slideOutVertically(targetOffsetY = { it / 10 })
        ) {
            IsometricAutoScannerScreen(
                onHaptic = { haptic(18L) },
                onClose = { showScanner = false },
                onSolved = { moves ->
                    haptic(25L)
                    showScanner = false
                    showTips = true
                    val cnt = moves.split(' ').count { it.isNotBlank() }
                    selectFormula(
                        FormulaItem(
                            id = "iso-auto-solve",
                            stage = CfopStage.PLL,
                            category = "三面连扫",
                            code = "最简解",
                            name = "三面自动扫描 · 最优解 ($cnt 步)",
                            alg = moves,
                            is2Look = false,
                            tips = "由三面同步等轴测免点击连扫 + CIEDE2000 校色 + min2phase 算法求得的 $cnt 步最简复原公式。"
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun GlassMiniButton(icon: LgIcon, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        GlassVectorIcon(icon, InkPrimary, 17.dp)
    }
}

@Composable
private fun GlassPillIcon(icon: LgIcon, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) InkPrimary else GlassCardBg)
            .border(1.dp, Color(0x2418181B), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        GlassVectorIcon(icon, if (active) Color.White else InkPrimary, 16.dp)
    }
}

@Composable
private fun AnimatedSegmentedSwitch(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassGroove)
            .padding(3.dp)
    ) {
        val segW = maxWidth / options.size
        val offsetX by animateDpAsState(
            targetValue = segW * selectedIndex,
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 460f),
            label = "segGlider"
        )
        // 液态玻璃滑动胶囊指示器
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(segW)
                .fillMaxHeight()
                .shadow(3.dp, RoundedCornerShape(9.dp), ambientColor = Color(0x28000000))
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xF5FFFFFF))
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { idx, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(idx) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedIndex == idx) InkPrimary else InkSecondary
                    )
                }
            }
        }
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

    // 轻触任意空白区域自动合拢
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x22000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(top = 66.dp, end = 14.dp, bottom = 152.dp, start = 36.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .liquidGlassSurface(corner = 26.dp, elevation = 20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnimatedSegmentedSwitch(
                options = listOf("公式库", "3D 沙盒", "控制与计时"),
                selectedIndex = selectedTab,
                onSelect = onSelectTab
            )

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "consoleTabAnim"
            ) { tab ->
                when (tab) {
                    0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    .border(1.dp, Color(0x2618181B), RoundedCornerShape(12.dp))
                                    .clickable { only2Look = !only2Look }
                                    .padding(horizontal = 11.dp, vertical = 14.dp)
                            ) {
                                Text("2-Look", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (only2Look) Color.White else InkPrimary)
                            }
                        }

                        LazyColumn(modifier = Modifier.heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(filtered, key = { it.id }) { item ->
                                val isDone = masteredIds.contains(item.id)
                                val isSel = item.id == activeId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(if (isSel) Color.White else GlassCardBg)
                                        .border(if (isSel) 1.5.dp else 1.dp, if (isSel) InkPrimary else Color(0x2218181B), RoundedCornerShape(13.dp))
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
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(if (isDone) InkPrimary else Color(0x14000000))
                                            .clickable { onToggleMaster(item.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        GlassVectorIcon(LgIcon.CHECK, if (isDone) Color.White else Color(0xFF94A3B8), 14.dp)
                                    }
                                }
                            }
                        }
                    }

                    1 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val moves = listOf(
                            listOf("R", "R'", "R2", "L", "L'", "L2"),
                            listOf("U", "U'", "U2", "D", "D'", "D2"),
                            listOf("F", "F'", "F2", "B", "B'", "B2"),
                            listOf("r", "r'", "M", "M'", "M2", "y")
                        )
                        Text("点击指令即可驱动 3D 磁力魔方实时旋转：", fontSize = 11.5.sp, color = InkSecondary)
                        moves.forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { mv ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(GlassCardBg)
                                            .border(1.dp, Color(0x2218181B), RoundedCornerShape(10.dp))
                                            .clickable { onSandboxMove(mv) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(mv, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = InkPrimary)
                                    }
                                }
                            }
                        }
                    }

                    else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

                        AnimatedSegmentedSwitch(
                            options = listOf("焦点蒙版", "全彩模式"),
                            selectedIndex = if (focusMask) 0 else 1,
                            onSelect = { onToggleMask(it == 0) }
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(GlassCardBg)
                                .border(1.dp, Color(0x2218181B), RoundedCornerShape(16.dp))
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
}

/**
 * 免点击「三面同步等轴测连扫」界面：
 * - 第 1 下对准（自动锁拍 3 面）：顶 U 黄 + 左前 F 绿 + 右前 R 红
 * - 翻转露出另外 3 面（自动锁拍 3 面）：顶 D 白 + 左前 B 蓝 + 右前 L 橙
 * 全程无需点拍照按钮，CameraX ImageAnalysis 零拷贝 YUV 采色 (<1.5ms/帧)，绝不卡顿！
 */
@Composable
private fun IsometricAutoScannerScreen(
    onHaptic: () -> Unit,
    onClose: () -> Unit,
    onSolved: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

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

    // 扫描阶段：0 = 扫第 1 组 3 面 (U, F, R)；1 = 扫第 2 组 3 面 (D, B, L)；2 = 6 面全齐待确认求解
    var scanPhase by remember { mutableIntStateOf(0) }
    var lockProgress by remember { mutableFloatStateOf(0f) }
    var livePreviewColors by remember { mutableStateOf(List(27) { Color.Transparent }) }
    val rawRgbFaces = remember { mutableStateMapOf<Char, List<IntArray>>() }
    val resolvedFaces = remember { mutableStateMapOf<Char, CharArray>() }
    var statusText by remember { mutableStateOf("第 1/2 步：将魔方斜着对准六边形，同时露出【黄顶 + 绿左 + 红右】，稳住自动锁拍") }
    var selectedEditFace by remember { mutableStateOf('U') }

    // 帧稳定性追踪器
    val stableState = remember {
        object {
            var lastRgb: List<IntArray>? = null
            var stableFrames = 0
            var lastSampleTs = 0L
            var cooldownUntil = 0L
        }
    }

    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }

    fun commitThreeFaces(rgb27: List<IntArray>, phase: Int) {
        val keys = if (phase == 0) listOf('U', 'F', 'R') else listOf('D', 'B', 'L')
        for (slot in 0..2) {
            val grid9 = rgb27.subList(slot * 9, slot * 9 + 9)
            rawRgbFaces[keys[slot]] = grid9
            resolvedFaces[keys[slot]] = RubiksColorResolver.previewSingleFace(grid9, keys[slot])
        }
        onHaptic()
        if (phase == 0) {
            scanPhase = 1
            lockProgress = 0f
            statusText = "已自动锁定 U/F/R 3 个面！第 2/2 步：把魔方翻转过来对准【白顶 + 蓝左 + 橙右】，稳住自动完成"
        } else {
            // 全部 6 面采齐，执行 CIEDE2000 6 中心全局均衡校色
            val rgb54 = MutableList(54) { intArrayOf(0, 0, 0) }
            for ((k, grid) in rawRgbFaces) {
                val off = FACE_OFFSET[k] ?: continue
                for (i in 0 until 9) rgb54[off + i] = grid[i]
            }
            val resolved54 = RubiksColorResolver.resolve54Facelets(rgb54)
            for ((k, off) in FACE_OFFSET) {
                resolvedFaces[k] = resolved54.sliceArray(off until off + 9)
            }
            scanPhase = 2
            lockProgress = 1f
            statusText = "6 面全部自动识别并完成 CIEDE2000 校色！确认色块无误点下方「min2phase 求解」"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE8ECF2))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("三面同步免点击连扫 (${rawRgbFaces.size}/6)", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = InkPrimary)
                Text("只需对准 2 下 · 原生零依赖 极速识别", fontSize = 11.sp, color = InkSecondary)
            }
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(999.dp)) {
                Text("返回 3D", fontSize = 12.sp, color = InkPrimary)
            }
        }

        // CameraX 预览 + 等轴测三面六边形引导框
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
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
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(analyzerExecutor) { image ->
                                val now = System.currentTimeMillis()
                                if (scanPhase < 2 && now - stableState.lastSampleTs >= 140 && now >= stableState.cooldownUntil) {
                                    stableState.lastSampleTs = now
                                    val rgb27 = YuvIsometricSampler.sample27Points(image)
                                    val prev = stableState.lastRgb
                                    val isStable = prev != null && rgb27.indices.all { i ->
                                        val dr = kotlin.math.abs(rgb27[i][0] - prev[i][0])
                                        val dg = kotlin.math.abs(rgb27[i][1] - prev[i][1])
                                        val db = kotlin.math.abs(rgb27[i][2] - prev[i][2])
                                        (dr + dg + db) < 42
                                    }
                                    stableState.lastRgb = rgb27
                                    if (isStable) stableState.stableFrames++ else stableState.stableFrames = 0

                                    val prog = (stableState.stableFrames / 5f).coerceIn(0f, 1f)
                                    val dotCols = rgb27.map { Color(it[0], it[1], it[2]) }
                                    val curPhase = scanPhase

                                    scope.launch(Dispatchers.Main) {
                                        livePreviewColors = dotCols
                                        lockProgress = prog
                                        if (stableState.stableFrames >= 5 && scanPhase == curPhase && curPhase < 2) {
                                            stableState.stableFrames = 0
                                            stableState.cooldownUntil = System.currentTimeMillis() + 1200L
                                            commitThreeFaces(rgb27, curPhase)
                                        }
                                    }
                                }
                                image.close()
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            } else {
                Text("请授予相机权限以开启三面连扫", color = Color.White, fontSize = 13.sp)
            }

            // 等轴测 3 面六边形引导线 + 27 格实时采样色点
            Canvas(modifier = Modifier.fillMaxSize()) {
                val side = kotlin.math.min(size.width, size.height)
                val ox = (size.width - side) * 0.5f
                val oy = (size.height - side) * 0.5f
                val cx = ox + side * 0.5f
                val cy = oy + side * 0.5f
                val r = side * 0.34f
                val dx = r * cos(Math.toRadians(30.0)).toFloat()
                val dy = r * sin(Math.toRadians(30.0)).toFloat()

                val hex = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + dx, cy - dy)
                    lineTo(cx + dx, cy + dy)
                    lineTo(cx, cy + r)
                    lineTo(cx - dx, cy + dy)
                    lineTo(cx - dx, cy - dy)
                    close()
                }
                val guideColor = if (lockProgress > 0.65f) Color(0xFF10B981) else Color.White.copy(alpha = 0.88f)
                val st = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(hex, guideColor, style = st)
                drawLine(guideColor, Offset(cx, cy), Offset(cx - dx, cy - dy), 4f)
                drawLine(guideColor, Offset(cx, cy), Offset(cx + dx, cy - dy), 4f)
                drawLine(guideColor, Offset(cx, cy), Offset(cx, cy + r), 4f)

                YuvIsometricSampler.isoPoints.forEachIndexed { idx, pt ->
                    val px = ox + pt.nx * side
                    val py = oy + pt.ny * side
                    val fill = livePreviewColors.getOrElse(idx) { Color.Transparent }
                    drawCircle(fill, radius = 11f, center = Offset(px, py))
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = 11f, center = Offset(px, py), style = Stroke(2.2f))
                }
            }

            // 顶部自动锁拍进度条
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(14.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x44FFFFFF))
            ) {
                val animProg by animateFloatAsState(lockProgress, label = "lockProg")
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animProg)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF10B981))
                )
            }
        }

        // 状态与 6 面微调校色栏
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassSurface(corner = 18.dp, elevation = 8.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(statusText, fontSize = 12.sp, color = InkPrimary, fontWeight = FontWeight.Medium)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf('U', 'F', 'R', 'D', 'B', 'L').forEach { fk ->
                    val done = resolvedFaces.containsKey(fk)
                    val isSel = selectedEditFace == fk
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(when { isSel -> InkPrimary; done -> Color(0x2218181B); else -> GlassCardBg })
                            .clickable { selectedEditFace = fk }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (done) "$fk✓" else "$fk", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (isSel) Color.White else InkPrimary)
                    }
                }
            }

            if (resolvedFaces.containsKey(selectedEditFace)) {
                val grid = resolvedFaces[selectedEditFace]!!
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (r in 0 until 3) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (c in 0 until 3) {
                                    val i = r * 3 + c
                                    val ch = grid[i]
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(FACE_COLOR_MAP[ch] ?: Color.Gray)
                                            .border(1.dp, Color(0x4418181B), RoundedCornerShape(4.dp))
                                            .clickable(enabled = i != 4) {
                                                val cp = grid.copyOf()
                                                cp[i] = COLOR_CYCLE[(COLOR_CYCLE.indexOf(ch) + 1) % COLOR_CYCLE.size]
                                                resolvedFaces[selectedEditFace] = cp
                                            }
                                    )
                                }
                            }
                        }
                    }
                    Text("点色块可微调光照误差（$selectedEditFace 面）", fontSize = 11.sp, color = InkSecondary)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        rawRgbFaces.clear(); resolvedFaces.clear()
                        scanPhase = 0; lockProgress = 0f
                        stableState.stableFrames = 0
                        statusText = "已重置：请将【黄顶 + 绿左 + 红右】斜着对准六边形自动扫前 3 面"
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("重扫", fontSize = 12.sp, color = InkPrimary)
                }

                Button(
                    onClick = {
                        if (resolvedFaces.size < 6) {
                            statusText = "请先完成 2 步三面连扫（当前已采 ${resolvedFaces.size}/6 面）"
                            return@Button
                        }
                        scope.launch(Dispatchers.Default) {
                            val state = CharArray(54)
                            for ((k, off) in FACE_OFFSET) {
                                val arr = resolvedFaces[k] ?: CharArray(9) { k }
                                for (i in 0 until 9) state[off + i] = arr[i]
                            }
                            val res = CubeSolverBridge.solve(String(state))
                            withContext(Dispatchers.Main) {
                                if (res.ok) onSolved(res.moves)
                                else statusText = res.error
                            }
                        }
                    },
                    modifier = Modifier.weight(1.5f).height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("min2phase 最简求解", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}
