package com.cfop.lab.engine

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class CubeGLRenderer(val cubeState: Cube3DState) : GLSurfaceView.Renderer {

    private var programId = 0
    private var uMvpLoc = 0
    private var uModelLoc = 0
    private var uColorLoc = 0
    private var uGlossLoc = 0
    private var uCamPosLoc = 0

    private lateinit var boxBuf: FloatBuffer
    private var boxVertexCount = 0
    private lateinit var stickerBuf: FloatBuffer
    private var stickerVertexCount = 0

    // 360° 全向无死角四元数旋转 [x, y, z, w]
    private val defaultQuat = eulerToQuat(0.46f, -0.62f, 0f)
    private val curQuat = defaultQuat.copyOf()
    private val targetQuat = defaultQuat.copyOf()

    @Volatile var cameraDistance = 11.5f
    @Volatile var targetDistance = 11.5f
    private var defaultDistance = 11.5f

    private val projMat = FloatArray(16)
    private val viewMat = FloatArray(16)
    private val viewRotMat = FloatArray(16)
    private val modelMat = FloatArray(16)
    private val mvpMat = FloatArray(16)
    private val tmpMat = FloatArray(16)
    private val animMat = FloatArray(16)
    private val faceRotMat = FloatArray(16)

    // 竞速级高饱和度 WCA 配色 (RGB)
    private val faceColors = mapOf(
        'U' to floatArrayOf(1.00f, 0.84f, 0.00f), // 黄
        'D' to floatArrayOf(0.97f, 0.98f, 0.99f), // 白
        'F' to floatArrayOf(0.00f, 0.66f, 0.32f), // 绿
        'B' to floatArrayOf(0.00f, 0.30f, 0.72f), // 蓝
        'R' to floatArrayOf(0.82f, 0.08f, 0.20f), // 红
        'L' to floatArrayOf(1.00f, 0.36f, 0.00f)  // 橙
    )
    private val dimColor = floatArrayOf(0.20f, 0.23f, 0.28f)
    private val plasticColor = floatArrayOf(0.09f, 0.10f, 0.13f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 苹果柔光箱工作室浅灰白背景
        GLES30.glClearColor(0.91f, 0.93f, 0.95f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        programId = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvpLoc = GLES30.glGetUniformLocation(programId, "uMVP")
        uModelLoc = GLES30.glGetUniformLocation(programId, "uModel")
        uColorLoc = GLES30.glGetUniformLocation(programId, "uBaseColor")
        uGlossLoc = GLES30.glGetUniformLocation(programId, "uGloss")
        uCamPosLoc = GLES30.glGetUniformLocation(programId, "uCamPos")

        val boxVerts = buildBoxVertices(0.475f)
        boxVertexCount = boxVerts.size / 6
        boxBuf = toFloatBuffer(boxVerts)

        val stVerts = buildStickerQuadVertices(0.395f, 0.486f)
        stickerVertexCount = stVerts.size / 6
        stickerBuf = toFloatBuffer(stVerts)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val w = max(1, width)
        val h = max(1, height)
        GLES30.glViewport(0, 0, w, h)
        val aspect = w.toFloat() / h.toFloat()
        Matrix.perspectiveM(projMat, 0, 36f, aspect, 0.5f, 100f)
        // 完美适配 Redmi K70 Pro (3200x1440, 20:9) 竖屏视距
        defaultDistance = if (aspect < 1f) (6.2f / aspect).coerceIn(10.5f, 15.8f) else 9.0f
        cameraDistance = defaultDistance
        targetDistance = defaultDistance
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        cubeState.tick(System.nanoTime() / 1_000_000L)

        // 四元数平滑球面插值 + 视距阻尼
        synchronized(targetQuat) {
            slerpInto(curQuat, targetQuat, 0.18f)
        }
        cameraDistance += (targetDistance - cameraDistance) * 0.18f

        Matrix.setLookAtM(viewMat, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        quatToMatrix(curQuat, viewRotMat)

        GLES30.glUseProgram(programId)
        GLES30.glUniform3f(uCamPosLoc, 0f, 0f, cameraDistance)

        val activeMv = cubeState.getActiveMove()
        val animDeg = cubeState.animCurrentDeg
        if (activeMv != null) {
            Matrix.setRotateM(
                animMat, 0, animDeg,
                if (activeMv.axis == 0) 1f else 0f,
                if (activeMv.axis == 1) 1f else 0f,
                if (activeMv.axis == 2) 1f else 0f
            )
        } else {
            Matrix.setIdentityM(animMat, 0)
        }

        val step = 1.02f
        val vpMat = FloatArray(16)
        Matrix.multiplyMM(vpMat, 0, projMat, 0, viewMat, 0)

        // 先绘制深色磁力内核块（防止旋转 45° 时露空）
        Matrix.setIdentityM(modelMat, 0)
        Matrix.multiplyMM(tmpMat, 0, viewRotMat, 0, modelMat, 0)
        Matrix.scaleM(tmpMat, 0, 2.2f, 2.2f, 2.2f)
        Matrix.multiplyMM(mvpMat, 0, vpMat, 0, tmpMat, 0)
        drawMesh(boxBuf, boxVertexCount, tmpMat, mvpMat, plasticColor, 0.25f)

        // 逐块绘制 26 颗磁力角块/棱块/中心块 + 立体微凸清漆贴纸
        for (c in cubeState.cubies) {
            Matrix.setIdentityM(modelMat, 0)
            Matrix.translateM(modelMat, 0, c.gx * step, c.gy * step, c.gz * step)
            Matrix.multiplyMM(tmpMat, 0, modelMat, 0, c.rotMat, 0)

            val worldMat = FloatArray(16)
            if (activeMv != null && activeMv.filter(c.gx, c.gy, c.gz)) {
                val animLocal = FloatArray(16)
                Matrix.multiplyMM(animLocal, 0, animMat, 0, tmpMat, 0)
                Matrix.multiplyMM(worldMat, 0, viewRotMat, 0, animLocal, 0)
            } else {
                Matrix.multiplyMM(worldMat, 0, viewRotMat, 0, tmpMat, 0)
            }

            // 1) 深邃黑塑胶块本体
            Matrix.multiplyMM(mvpMat, 0, vpMat, 0, worldMat, 0)
            drawMesh(boxBuf, boxVertexCount, worldMat, mvpMat, plasticColor, 0.35f)

            // 2) 外露面的微凸高光清漆贴纸
            if (c.homeZ == 1) drawSticker(c, 'F', 0f, 0f, 0f, worldMat, vpMat)
            if (c.homeZ == -1) drawSticker(c, 'B', 180f, 0f, 1f, worldMat, vpMat)
            if (c.homeX == 1) drawSticker(c, 'R', 90f, 0f, 1f, worldMat, vpMat)
            if (c.homeX == -1) drawSticker(c, 'L', -90f, 0f, 1f, worldMat, vpMat)
            if (c.homeY == 1) drawSticker(c, 'U', -90f, 1f, 0f, worldMat, vpMat)
            if (c.homeY == -1) drawSticker(c, 'D', 90f, 1f, 0f, worldMat, vpMat)
        }
    }

    private fun drawSticker(
        c: Cubie, face: Char, deg: Float, ax: Float, ay: Float,
        cubieWorld: FloatArray, vpMat: FloatArray
    ) {
        if (deg != 0f) Matrix.setRotateM(faceRotMat, 0, deg, ax, ay, 0f)
        else Matrix.setIdentityM(faceRotMat, 0)

        val stWorld = FloatArray(16)
        Matrix.multiplyMM(stWorld, 0, cubieWorld, 0, faceRotMat, 0)
        Matrix.multiplyMM(mvpMat, 0, vpMat, 0, stWorld, 0)

        val focused = cubeState.isStickerFocused(c, face)
        val col = if (focused) (faceColors[face] ?: dimColor) else dimColor
        drawMesh(stickerBuf, stickerVertexCount, stWorld, mvpMat, col, if (focused) 0.95f else 0.15f)
    }

    private fun drawMesh(
        buf: FloatBuffer, count: Int,
        model: FloatArray, mvp: FloatArray,
        rgb: FloatArray, gloss: Float
    ) {
        GLES30.glUniformMatrix4fv(uModelLoc, 1, false, model, 0)
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
        GLES30.glUniform3f(uColorLoc, rgb[0], rgb[1], rgb[2])
        GLES30.glUniform1f(uGlossLoc, gloss)

        val stride = 6 * 4
        buf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, buf)
        buf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, buf)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    fun rotateByDrag(dx: Float, dy: Float) {
        synchronized(targetQuat) {
            val qYaw = axisAngleToQuat(0f, 1f, 0f, dx * 0.0085f)
            val qPitch = axisAngleToQuat(1f, 0f, 0f, dy * 0.0085f)
            val step1 = mulQuat(qYaw, targetQuat)
            val step2 = mulQuat(qPitch, step1)
            normalizeQuat(step2)
            System.arraycopy(step2, 0, targetQuat, 0, 4)
        }
    }

    fun zoomByFactor(scaleFactor: Float) {
        targetDistance = (targetDistance / scaleFactor).coerceIn(5.5f, 22.0f)
    }

    fun zoomByDelta(delta: Float) {
        targetDistance = (targetDistance + delta).coerceIn(5.5f, 22.0f)
    }

    fun resetCamera() {
        synchronized(targetQuat) {
            System.arraycopy(defaultQuat, 0, targetQuat, 0, 4)
        }
        targetDistance = defaultDistance
    }

    // ---- Geometry & Math Helpers ----
    private fun toFloatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr); position(0)
        }

    private fun buildStickerQuadVertices(s: Float, z: Float): FloatArray = floatArrayOf(
        -s, -s, z, 0f, 0f, 1f,   s, -s, z, 0f, 0f, 1f,   s,  s, z, 0f, 0f, 1f,
        -s, -s, z, 0f, 0f, 1f,   s,  s, z, 0f, 0f, 1f,  -s,  s, z, 0f, 0f, 1f
    )

    private fun buildBoxVertices(s: Float): FloatArray {
        val v = mutableListOf<Float>()
        fun quad(
            x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float, x4: Float, y4: Float, z4: Float,
            nx: Float, ny: Float, nz: Float
        ) {
            val pts = floatArrayOf(
                x1, y1, z1, nx, ny, nz, x2, y2, z2, nx, ny, nz, x3, y3, z3, nx, ny, nz,
                x1, y1, z1, nx, ny, nz, x3, y3, z3, nx, ny, nz, x4, y4, z4, nx, ny, nz
            )
            for (f in pts) v.add(f)
        }
        quad(-s, -s,  s,  s, -s,  s,  s,  s,  s, -s,  s,  s,  0f,  0f,  1f) // F
        quad( s, -s, -s, -s, -s, -s, -s,  s, -s,  s,  s, -s,  0f,  0f, -1f) // B
        quad( s, -s,  s,  s, -s, -s,  s,  s, -s,  s,  s,  s,  1f,  0f,  0f) // R
        quad(-s, -s, -s, -s, -s,  s, -s,  s,  s, -s,  s, -s, -1f,  0f,  0f) // L
        quad(-s,  s,  s,  s,  s,  s,  s,  s, -s, -s,  s, -s,  0f,  1f,  0f) // U
        quad(-s, -s, -s,  s, -s, -s,  s, -s,  s, -s, -s,  s,  0f, -1f,  0f) // D
        return v.toFloatArray()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fs)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vShader)
        GLES30.glAttachShader(prog, fShader)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vShader)
        GLES30.glDeleteShader(fShader)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, src)
        GLES30.glCompileShader(id)
        return id
    }

    private fun axisAngleToQuat(ax: Float, ay: Float, az: Float, rad: Float): FloatArray {
        val h = rad * 0.5f
        val s = sin(h)
        return floatArrayOf(ax * s, ay * s, az * s, cos(h))
    }

    private fun eulerToQuat(rx: Float, ry: Float, rz: Float): FloatArray {
        val qx = axisAngleToQuat(1f, 0f, 0f, rx)
        val qy = axisAngleToQuat(0f, 1f, 0f, ry)
        val qz = axisAngleToQuat(0f, 0f, 1f, rz)
        return mulQuat(mulQuat(qx, qy), qz)
    }

    private fun mulQuat(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
    )

    private fun normalizeQuat(q: FloatArray) {
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).coerceAtLeast(1e-6f)
        for (i in 0..3) q[i] /= len
    }

    private fun slerpInto(cur: FloatArray, tgt: FloatArray, alpha: Float) {
        var dot = cur[0] * tgt[0] + cur[1] * tgt[1] + cur[2] * tgt[2] + cur[3] * tgt[3]
        val sign = if (dot < 0f) -1f else 1f
        dot *= sign
        for (i in 0..3) cur[i] += (tgt[i] * sign - cur[i]) * alpha
        normalizeQuat(cur)
    }

    private fun quatToMatrix(q: FloatArray, m: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        m[0] = 1f - 2f * (yy + zz); m[1] = 2f * (xy + wz);      m[2] = 2f * (xz - wy);      m[3] = 0f
        m[4] = 2f * (xy - wz);      m[5] = 1f - 2f * (xx + zz); m[6] = 2f * (yz + wx);      m[7] = 0f
        m[8] = 2f * (xz + wy);      m[9] = 2f * (yz - wx);      m[10] = 1f - 2f * (xx + yy); m[11] = 0f
        m[12] = 0f;                 m[13] = 0f;                 m[14] = 0f;                 m[15] = 1f
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            out vec3 vNormal;
            out vec3 vWorldPos;
            void main() {
                vec4 wp = uModel * vec4(aPos, 1.0);
                vWorldPos = wp.xyz;
                vNormal = normalize(mat3(uModel) * aNormal);
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec3 vNormal;
            in vec3 vWorldPos;
            uniform vec3 uBaseColor;
            uniform float uGloss;
            uniform vec3 uCamPos;
            out vec4 fragColor;
            void main() {
                vec3 N = normalize(vNormal);
                vec3 V = normalize(uCamPos - vWorldPos);
                vec3 L1 = normalize(vec3(5.0, 9.0, 8.0));
                vec3 L2 = normalize(vec3(-7.0, 3.0, -5.0));

                float diff1 = max(dot(N, L1), 0.0);
                float diff2 = max(dot(N, L2), 0.0) * 0.32;
                float hemi = 0.55 + 0.25 * N.y;

                // 清漆高光反射（模拟柔光箱高光条）
                vec3 H1 = normalize(L1 + V);
                float spec = pow(max(dot(N, H1), 0.0), mix(18.0, 96.0, uGloss)) * (0.28 + 0.55 * uGloss);
                float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.16 * uGloss;

                vec3 color = uBaseColor * (hemi * 0.58 + diff1 * 0.52 + diff2) + vec3(spec + fresnel);
                fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """
    }
}
