package com.cfop.lab.engine

import android.opengl.Matrix
import com.cfop.lab.model.CfopStage
import kotlin.math.abs
import kotlin.math.pow

data class MoveSpec(
    val token: String,
    val axis: Int, // 0=X, 1=Y, 2=Z
    val angleDeg: Float,
    val filter: (Int, Int, Int) -> Boolean
)

class Cubie(val homeX: Int, val homeY: Int, val homeZ: Int) {
    var gx: Int = homeX
    var gy: Int = homeY
    var gz: Int = homeZ
    val rotMat: FloatArray = FloatArray(16)

    init {
        Matrix.setIdentityM(rotMat, 0)
    }

    fun reset() {
        gx = homeX; gy = homeY; gz = homeZ
        Matrix.setIdentityM(rotMat, 0)
    }
}

class Cube3DState {
    val cubies: List<Cubie> = buildList {
        for (x in -1..1) for (y in -1..1) for (z in -1..1) {
            if (x == 0 && y == 0 && z == 0) continue
            add(Cubie(x, y, z))
        }
    }

    var focusMask: Boolean = true
    var activeStage: CfopStage = CfopStage.OLL

    private val queue = ArrayDeque<Pair<MoveSpec, (() -> Unit)?>>()
    private var activeMove: MoveSpec? = null
    private var activeCallback: (() -> Unit)? = null
    private var animStartMs: Long = 0L
    private var animDurationMs: Float = 240f
    var animCurrentDeg: Float = 0f
        private set
    var speedMultiplier: Float = 1.0f

    var onMoveStartHaptic: (() -> Unit)? = null

    @Synchronized
    fun resetSolved() {
        queue.clear()
        activeMove = null
        activeCallback = null
        animCurrentDeg = 0f
        for (c in cubies) c.reset()
    }

    @Synchronized
    fun setupFormulaAtStep(tokens: List<String>, stepIndex: Int) {
        resetSolved()
        val inv = invertAlgorithm(tokens)
        for (m in inv) applyMoveInstant(m)
        val clamped = stepIndex.coerceIn(0, tokens.size)
        for (i in 0 until clamped) applyMoveInstant(tokens[i])
    }

    @Synchronized
    fun applyMoveInstant(token: String) {
        val spec = parseMove(token) ?: return
        commitMove(spec)
    }

    @Synchronized
    fun enqueueMove(token: String, onDone: (() -> Unit)? = null) {
        val spec = parseMove(token) ?: run { onDone?.invoke(); return }
        queue.addLast(spec to onDone)
        if (activeMove == null) startNext(System.nanoTime() / 1_000_000L)
    }

    @Synchronized
    fun isMoving(): Boolean = activeMove != null || queue.isNotEmpty()

    @Synchronized
    fun getActiveMove(): MoveSpec? = activeMove

    @Synchronized
    fun tick(nowMs: Long) {
        val mv = activeMove ?: run {
            if (queue.isNotEmpty()) startNext(nowMs)
            return
        }
        val t = ((nowMs - animStartMs) / animDurationMs).coerceIn(0f, 1f)
        animCurrentDeg = mv.angleDeg * easeMagneticSnap(t)
        if (t >= 1f) {
            commitMove(mv)
            val cb = activeCallback
            activeMove = null
            activeCallback = null
            animCurrentDeg = 0f
            cb?.invoke()
            if (queue.isNotEmpty()) startNext(nowMs)
        }
    }

    private fun startNext(nowMs: Long) {
        if (queue.isEmpty()) return
        val (spec, cb) = queue.removeFirst()
        activeMove = spec
        activeCallback = cb
        animStartMs = nowMs
        val base = if (abs(spec.angleDeg) > 120f) 330f else 220f
        animDurationMs = base / speedMultiplier.coerceIn(0.4f, 3.0f)
        animCurrentDeg = 0f
        onMoveStartHaptic?.invoke()
    }

    // 磁吸微回弹物理手感曲线（4.2% 过冲 + 干脆磁吸归位）
    private fun easeMagneticSnap(t: Float): Float {
        if (t >= 1f) return 1f
        return if (t < 0.78f) {
            val u = t / 0.78f
            1.042f * (1f - (1f - u).pow(3))
        } else {
            val u = (t - 0.78f) / 0.22f
            1.042f - 0.042f * (1f - (1f - u).pow(2))
        }
    }

    private fun commitMove(spec: MoveSpec) {
        val rot = FloatArray(16)
        Matrix.setRotateM(
            rot, 0, spec.angleDeg,
            if (spec.axis == 0) 1f else 0f,
            if (spec.axis == 1) 1f else 0f,
            if (spec.axis == 2) 1f else 0f
        )
        val tmpPos = FloatArray(4)
        val resPos = FloatArray(4)
        val tmpMat = FloatArray(16)

        for (c in cubies) {
            if (!spec.filter(c.gx, c.gy, c.gz)) continue
            tmpPos[0] = c.gx.toFloat(); tmpPos[1] = c.gy.toFloat(); tmpPos[2] = c.gz.toFloat(); tmpPos[3] = 1f
            Matrix.multiplyMV(resPos, 0, rot, 0, tmpPos, 0)
            c.gx = Math.round(resPos[0])
            c.gy = Math.round(resPos[1])
            c.gz = Math.round(resPos[2])

            Matrix.multiplyMM(tmpMat, 0, rot, 0, c.rotMat, 0)
            // 零误差正交矩阵防漂移：将每个旋转基向量严格吸附到 {-1, 0, 1}
            for (i in 0 until 16) {
                val v = tmpMat[i]
                c.rotMat[i] = when {
                    v > 0.5f -> 1f
                    v < -0.5f -> -1f
                    else -> 0f
                }
            }
            c.rotMat[15] = 1f
        }
    }

    fun isStickerFocused(c: Cubie, face: Char): Boolean {
        if (!focusMask) return true
        return when (activeStage) {
            CfopStage.CROSS -> {
                val isCenter = (abs(c.homeX) + abs(c.homeY) + abs(c.homeZ)) == 1
                val isWhiteEdge = c.homeY == -1 && (abs(c.homeX) + abs(c.homeZ) == 1)
                isCenter || isWhiteEdge
            }
            CfopStage.F2L -> c.homeY <= 0
            CfopStage.OLL -> c.homeY <= 0 || face == 'U'
            CfopStage.PLL -> true
        }
    }

    companion object {
        fun invertMove(tok: String): String = when {
            tok.endsWith("2") -> tok
            tok.endsWith("'") -> tok.dropLast(1)
            else -> "$tok'"
        }

        fun invertAlgorithm(tokens: List<String>): List<String> =
            tokens.asReversed().map { invertMove(it) }

        fun parseMove(raw: String): MoveSpec? {
            val clean = raw.trim()
            if (clean.isEmpty()) return null
            var base = clean[0]
            var rest = clean.substring(1)
            var wide = false
            if (rest.startsWith("w")) {
                wide = true
                rest = rest.substring(1)
            }
            if (base.isLowerCase() && base in listOf('r', 'l', 'u', 'd', 'f', 'b')) {
                wide = true
                base = base.uppercaseChar()
            }
            val mult = when (rest) {
                "'" -> -1f
                "2" -> 2f
                else -> 1f
            }
            return when (base) {
                'R' -> MoveSpec(clean, 0, -90f * mult) { x, _, _ -> if (wide) x >= 0 else x == 1 }
                'L' -> MoveSpec(clean, 0, 90f * mult) { x, _, _ -> if (wide) x <= 0 else x == -1 }
                'U' -> MoveSpec(clean, 1, -90f * mult) { _, y, _ -> if (wide) y >= 0 else y == 1 }
                'D' -> MoveSpec(clean, 1, 90f * mult) { _, y, _ -> if (wide) y <= 0 else y == -1 }
                'F' -> MoveSpec(clean, 2, -90f * mult) { _, _, z -> if (wide) z >= 0 else z == 1 }
                'B' -> MoveSpec(clean, 2, 90f * mult) { _, _, z -> if (wide) z <= 0 else z == -1 }
                'M' -> MoveSpec(clean, 0, 90f * mult) { x, _, _ -> x == 0 }
                'E' -> MoveSpec(clean, 1, 90f * mult) { _, y, _ -> y == 0 }
                'S' -> MoveSpec(clean, 2, -90f * mult) { _, _, z -> z == 0 }
                'x' -> MoveSpec(clean, 0, -90f * mult) { _, _, _ -> true }
                'y' -> MoveSpec(clean, 1, -90f * mult) { _, _, _ -> true }
                'z' -> MoveSpec(clean, 2, -90f * mult) { _, _, _ -> true }
                else -> null
            }
        }
    }
}
