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

/**
 * 商业级 OpenGL ES 3.0 磁力速拧魔方渲染器：
 * 1) 真实 3D 圆角倒角塑胶块网格（细分数=6，轮廓圆润如实体磁力魔方，绝非直角方块）；
 * 2) 片元着色器内用 SDF + fwidth() 抗锯齿解析绘制圆角微凸清漆贴纸，彻底根除拉远时的莫尔纹与 Z-Fighting；
 * 3) 程序化柔光箱工作室高光反射 + 底部柔光接触阴影，还原并超越 Three.js HTML 版质感；
 * 4) 360° 无死角四元数旋转 + 双指捏合缩放。
 */
class CubeGLRenderer(val cubeState: Cube3DState) : GLSurfaceView.Renderer {

    private var cubeProg = 0
    private var uMvpLoc = 0
    private var uModelLoc = 0
    private var uCamPosLoc = 0
    private var uFaceColorsLoc = 0
    private var uFaceFocusLoc = 0

    private var shadowProg = 0
    private var uShadowMvpLoc = 0

    private lateinit var cubieBuf: FloatBuffer
    private var cubieVertCount = 0
    private lateinit var shadowBuf: FloatBuffer

    private val defaultQuat = eulerToQuat(0.46f, -0.62f, 0f)
    private val curQuat = defaultQuat.copyOf()
    private val targetQuat = defaultQuat.copyOf()

    @Volatile var cameraDistance = 12.2f
    @Volatile var targetDistance = 12.2f
    private var defaultDistance = 12.2f

    private val projMat = FloatArray(16)
    private val viewMat = FloatArray(16)
    private val vpMat = FloatArray(16)
    private val viewRotMat = FloatArray(16)
    private val modelMat = FloatArray(16)
    private val mvpMat = FloatArray(16)
    private val tmpMat = FloatArray(16)
    private val animMat = FloatArray(16)
    private val worldMat = FloatArray(16)
    private val animLocal = FloatArray(16)

    // 高饱和度实体竞速配色（复刻 HTML 版质感：纯正黄/绿/红/蓝/橙/白）
    private val faceRgb = mapOf(
        'R' to floatArrayOf(0.88f, 0.11f, 0.28f), // +X 绯红
        'L' to floatArrayOf(1.00f, 0.42f, 0.00f), // -X 竞速橙
        'U' to floatArrayOf(1.00f, 0.84f, 0.00f), // +Y 明黄
        'D' to floatArrayOf(0.97f, 0.98f, 0.99f), // -Y 纯白
        'F' to floatArrayOf(0.00f, 0.72f, 0.36f), // +Z 翡翠绿
        'B' to floatArrayOf(0.00f, 0.34f, 0.82f)  // -Z 宝蓝
    )
    private val dimRgb = floatArrayOf(0.16f, 0.18f, 0.22f)
    private val noneRgb = floatArrayOf(0.08f, 0.09f, 0.12f)
    private val perCubieColors = FloatArray(18) // 6 faces * 3 RGB
    private val perCubieFocus = FloatArray(6)   // 1.0=亮贴纸, 0.2=暗蒙版贴纸, 0.0=无贴纸

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.918f, 0.933f, 0.953f, 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        cubeProg = buildProgram(CUBE_VS, CUBE_FS)
        uMvpLoc = GLES30.glGetUniformLocation(cubeProg, "uMVP")
        uModelLoc = GLES30.glGetUniformLocation(cubeProg, "uModel")
        uCamPosLoc = GLES30.glGetUniformLocation(cubeProg, "uCamPos")
        uFaceColorsLoc = GLES30.glGetUniformLocation(cubeProg, "uFaceColors")
        uFaceFocusLoc = GLES30.glGetUniformLocation(cubeProg, "uFaceFocus")

        shadowProg = buildProgram(SHADOW_VS, SHADOW_FS)
        uShadowMvpLoc = GLES30.glGetUniformLocation(shadowProg, "uMVP")

        // 生成真实圆角倒角立方体网格（48px 级圆润倒角轮廓）
        val verts = buildRoundedBoxMesh(halfSize = 0.488f, radius = 0.088f, segs = 5)
        cubieVertCount = verts.size / 9
        cubieBuf = toFloatBuffer(verts)

        val sq = 3.1f
        shadowBuf = toFloatBuffer(
            floatArrayOf(
                -sq, 0f, -sq, -1f, -1f,
                -sq, 0f,  sq, -1f,  1f,
                 sq, 0f,  sq,  1f,  1f,
                -sq, 0f, -sq, -1f, -1f,
                 sq, 0f,  sq,  1f,  1f,
                 sq, 0f, -sq,  1f, -1f
            )
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val w = max(1, width)
        val h = max(1, height)
        GLES30.glViewport(0, 0, w, h)
        val aspect = w.toFloat() / h.toFloat()
        Matrix.perspectiveM(projMat, 0, 35f, aspect, 0.5f, 100f)
        defaultDistance = if (aspect < 1f) (6.1f / aspect).coerceIn(10.8f, 15.6f) else 9.0f
        cameraDistance = defaultDistance
        targetDistance = defaultDistance
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val nowMs = System.nanoTime() / 1_000_000L
        cubeState.tick(nowMs)

        synchronized(targetQuat) {
            slerpInto(curQuat, targetQuat, 0.18f)
        }
        cameraDistance += (targetDistance - cameraDistance) * 0.18f

        Matrix.setLookAtM(viewMat, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMat, 0, projMat, 0, viewMat, 0)
        quatToMatrix(curQuat, viewRotMat)

        // 1) 绘制底部工作室柔光接触阴影
        drawContactShadow()

        // 2) 绘制 26 颗圆角倒角磁力块（带微弱悬浮呼吸感）
        val floatY = if (cubeState.isMoving()) 0f else sin(nowMs * 0.0014f) * 0.055f
        val activeMv = cubeState.getActiveMove()
        if (activeMv != null) {
            Matrix.setRotateM(
                animMat, 0, cubeState.animCurrentDeg,
                if (activeMv.axis == 0) 1f else 0f,
                if (activeMv.axis == 1) 1f else 0f,
                if (activeMv.axis == 2) 1f else 0f
            )
        } else {
            Matrix.setIdentityM(animMat, 0)
        }

        GLES30.glUseProgram(cubeProg)
        GLES30.glUniform3f(uCamPosLoc, 0f, 0f, cameraDistance)

        val stride = 9 * 4
        cubieBuf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, cubieBuf)
        cubieBuf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, cubieBuf)
        cubieBuf.position(6)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, cubieBuf)

        val step = 1.005f
        for (c in cubeState.cubies) {
            // 填充该块 6 个面的贴纸颜色与焦点状态 (+X=R, -X=L, +Y=U, -Y=D, +Z=F, -Z=B)
            fillFaceUniform(0, c.homeX == 1, c, 'R')
            fillFaceUniform(1, c.homeX == -1, c, 'L')
            fillFaceUniform(2, c.homeY == 1, c, 'U')
            fillFaceUniform(3, c.homeY == -1, c, 'D')
            fillFaceUniform(4, c.homeZ == 1, c, 'F')
            fillFaceUniform(5, c.homeZ == -1, c, 'B')

            GLES30.glUniform3fv(uFaceColorsLoc, 6, perCubieColors, 0)
            GLES30.glUniform1fv(uFaceFocusLoc, 6, perCubieFocus, 0)

            Matrix.setIdentityM(modelMat, 0)
            Matrix.translateM(modelMat, 0, c.gx * step, c.gy * step, c.gz * step)
            Matrix.multiplyMM(tmpMat, 0, modelMat, 0, c.rotMat, 0)

            if (activeMv != null && activeMv.filter(c.gx, c.gy, c.gz)) {
                Matrix.multiplyMM(animLocal, 0, animMat, 0, tmpMat, 0)
                Matrix.multiplyMM(worldMat, 0, viewRotMat, 0, animLocal, 0)
            } else {
                Matrix.multiplyMM(worldMat, 0, viewRotMat, 0, tmpMat, 0)
            }
            worldMat[13] += floatY

            Matrix.multiplyMM(mvpMat, 0, vpMat, 0, worldMat, 0)
            GLES30.glUniformMatrix4fv(uModelLoc, 1, false, worldMat, 0)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMat, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, cubieVertCount)
        }

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
    }

    private fun fillFaceUniform(slot: Int, hasSticker: Boolean, c: Cubie, face: Char) {
        val off = slot * 3
        if (!hasSticker) {
            perCubieColors[off] = noneRgb[0]; perCubieColors[off + 1] = noneRgb[1]; perCubieColors[off + 2] = noneRgb[2]
            perCubieFocus[slot] = 0f
            return
        }
        val focused = cubeState.isStickerFocused(c, face)
        val col = if (focused) (faceRgb[face] ?: dimRgb) else dimRgb
        perCubieColors[off] = col[0]; perCubieColors[off + 1] = col[1]; perCubieColors[off + 2] = col[2]
        perCubieFocus[slot] = if (focused) 1.0f else 0.25f
    }

    private fun drawContactShadow() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glUseProgram(shadowProg)
        Matrix.setIdentityM(modelMat, 0)
        Matrix.translateM(modelMat, 0, 0f, -2.55f, 0f)
        Matrix.multiplyMM(mvpMat, 0, vpMat, 0, modelMat, 0)
        GLES30.glUniformMatrix4fv(uShadowMvpLoc, 1, false, mvpMat, 0)

        val stride = 5 * 4
        shadowBuf.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, shadowBuf)
        shadowBuf.position(3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, shadowBuf)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun rotateByDrag(dx: Float, dy: Float) {
        synchronized(targetQuat) {
            val qYaw = axisAngleToQuat(0f, 1f, 0f, dx * 0.0085f)
            val qPitch = axisAngleToQuat(1f, 0f, 0f, dy * 0.0085f)
            val s1 = mulQuat(qYaw, targetQuat)
            val s2 = mulQuat(qPitch, s1)
            normalizeQuat(s2)
            System.arraycopy(s2, 0, targetQuat, 0, 4)
        }
    }

    fun zoomByFactor(scaleFactor: Float) {
        targetDistance = (targetDistance / scaleFactor).coerceIn(5.6f, 22.0f)
    }

    fun zoomByDelta(delta: Float) {
        targetDistance = (targetDistance + delta).coerceIn(5.6f, 22.0f)
    }

    fun resetCamera() {
        synchronized(targetQuat) {
            System.arraycopy(defaultQuat, 0, targetQuat, 0, 4)
        }
        targetDistance = defaultDistance
    }

    /**
     * 通过细分立方体 + 钳位球面投影生成真正的 3D 圆角倒角块（平滑法线 + 局部坐标用于 SDF 贴纸）
     * 每个顶点 9 个 float: pos(3), normal(3), localPos(3)
     */
    private fun buildRoundedBoxMesh(halfSize: Float, radius: Float, segs: Int): FloatArray {
        val inner = halfSize - radius
        val verts = mutableListOf<Float>()

        fun projectPoint(px: Float, py: Float, pz: Float): FloatArray {
            val cx = px.coerceIn(-inner, inner)
            val cy = py.coerceIn(-inner, inner)
            val cz = pz.coerceIn(-inner, inner)
            var dx = px - cx; var dy = py - cy; var dz = pz - cz
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len > 1e-6f) {
                dx /= len; dy /= len; dz /= len
            } else {
                // 在平面内部直接取最近面法线
                val ax = abs(px); val ay = abs(py); val az = abs(pz)
                when {
                    ax >= ay && ax >= az -> { dx = sign(px); dy = 0f; dz = 0f }
                    ay >= ax && ay >= az -> { dx = 0f; dy = sign(py); dz = 0f }
                    else -> { dx = 0f; dy = 0f; dz = sign(pz) }
                }
            }
            val rx = cx + dx * radius
            val ry = cy + dy * radius
            val rz = cz + dz * radius
            return floatArrayOf(rx, ry, rz, dx, dy, dz, rx / halfSize, ry / halfSize, rz / halfSize)
        }

        // 在 6 个面分别建立 (segs+1)x(segs+1) 非均匀网格（边角加密，呈现丝滑圆角倒角）
        val coords = FloatArray(segs + 1) { i ->
            val t = i.toFloat() / segs.toFloat() // 0..1
            // S 曲线加密边角采样
            val u = (t - 0.5f) * 2f // -1..1
            val s = sign(u) * (1f - (1f - abs(u)).pow(1.45f))
            s * halfSize
        }

        fun addFace(mapPt: (Float, Float) -> Triple<Float, Float, Float>) {
            val grid = Array(segs + 1) { r ->
                Array(segs + 1) { c ->
                    val (x, y, z) = mapPt(coords[c], coords[r])
                    projectPoint(x, y, z)
                }
            }
            for (r in 0 until segs) {
                for (c in 0 until segs) {
                    val v00 = grid[r][c]
                    val v10 = grid[r][c + 1]
                    val v11 = grid[r + 1][c + 1]
                    val v01 = grid[r + 1][c]
                    for (v in listOf(v00, v10, v11, v00, v11, v01)) {
                        for (f in v) verts.add(f)
                    }
                }
            }
        }

        addFace { u, v -> Triple(u, v, halfSize) }       // +Z (F)
        addFace { u, v -> Triple(-u, v, -halfSize) }     // -Z (B)
        addFace { u, v -> Triple(halfSize, v, -u) }      // +X (R)
        addFace { u, v -> Triple(-halfSize, v, u) }      // -X (L)
        addFace { u, v -> Triple(u, halfSize, -v) }      // +Y (U)
        addFace { u, v -> Triple(u, -halfSize, v) }      // -Y (D)

        return verts.toFloatArray()
    }

    private fun toFloatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr); position(0)
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
        val h = rad * 0.5f; val s = sin(h)
        return floatArrayOf(ax * s, ay * s, az * s, cos(h))
    }

    private fun eulerToQuat(rx: Float, ry: Float, rz: Float): FloatArray {
        return mulQuat(mulQuat(axisAngleToQuat(1f, 0f, 0f, rx), axisAngleToQuat(0f, 1f, 0f, ry)), axisAngleToQuat(0f, 0f, 1f, rz))
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
        private const val CUBE_VS = """#version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aLocal;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            out vec3 vNormal;
            out vec3 vWorldPos;
            out vec3 vLocal;
            void main() {
                vec4 wp = uModel * vec4(aPos, 1.0);
                vWorldPos = wp.xyz;
                vNormal = normalize(mat3(uModel) * aNormal);
                vLocal = aLocal;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        // SDF 圆角微凸贴纸 + fwidth() 抗莫尔纹滤波 + 柔光箱清漆反射
        private const val CUBE_FS = """#version 300 es
            precision highp float;
            in vec3 vNormal;
            in vec3 vWorldPos;
            in vec3 vLocal;
            uniform vec3 uCamPos;
            uniform vec3 uFaceColors[6];
            uniform float uFaceFocus[6];
            out vec4 fragColor;

            float sdRoundedBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + vec2(r);
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            void main() {
                vec3 a = abs(vLocal);
                int fIdx = 0;
                vec2 uv = vec2(0.0);
                float faceFlat = 0.0;

                if (a.x >= a.y && a.x >= a.z) {
                    fIdx = vLocal.x > 0.0 ? 0 : 1;
                    uv = vLocal.yz;
                    faceFlat = a.x;
                } else if (a.y >= a.x && a.y >= a.z) {
                    fIdx = vLocal.y > 0.0 ? 2 : 3;
                    uv = vLocal.xz;
                    faceFlat = a.y;
                } else {
                    fIdx = vLocal.z > 0.0 ? 4 : 5;
                    uv = vLocal.xy;
                    faceFlat = a.z;
                }

                // 解析计算圆角正方形贴纸边界，fwidth() 确保拉远时自动抗锯齿、彻底消除莫尔纹
                float d = sdRoundedBox(uv, vec2(0.795), 0.16);
                float aa = max(fwidth(d) * 1.15, 0.008);
                float flatMask = smoothstep(0.92, 0.985, faceFlat);
                float hasSticker = step(0.05, uFaceFocus[fIdx]);
                float stickerMask = (1.0 - smoothstep(-aa, aa, d)) * flatMask * hasSticker;

                vec3 plasticCol = vec3(0.085, 0.095, 0.12);
                vec3 stickerCol = uFaceColors[fIdx];
                vec3 baseCol = mix(plasticCol, stickerCol, stickerMask);

                // 贴纸边缘微凸高光唇边
                float rimLip = (1.0 - smoothstep(0.0, 0.055, abs(d))) * flatMask * hasSticker;

                vec3 N = normalize(vNormal);
                vec3 V = normalize(uCamPos - vWorldPos);
                vec3 L1 = normalize(vec3(4.5, 9.0, 8.0));
                vec3 L2 = normalize(vec3(-7.0, 3.5, 4.0));
                vec3 L3 = normalize(vec3(0.0, -6.0, 5.0));

                float diff1 = max(dot(N, L1), 0.0);
                float diff2 = max(dot(N, L2), 0.0);
                float diff3 = max(dot(N, L3), 0.0);
                float hemi = 0.68 + 0.24 * N.y;

                // 柔光箱清漆高光 + 塑胶倒角柔光轮廓
                float isVivid = step(0.5, uFaceFocus[fIdx]);
                float gloss = mix(0.32, mix(0.25, 0.92, isVivid), stickerMask);
                vec3 H1 = normalize(L1 + V);
                vec3 H2 = normalize(L2 + V);
                float spec1 = pow(max(dot(N, H1), 0.0), mix(24.0, 90.0, gloss)) * mix(0.22, 0.45, gloss);
                float spec2 = pow(max(dot(N, H2), 0.0), 32.0) * 0.14;
                float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.2) * mix(0.22, 0.14, stickerMask);

                vec3 lit = baseCol * (hemi * 0.62 + diff1 * 0.44 + diff2 * 0.18 + diff3 * 0.08)
                         + vec3(spec1 + spec2 + fresnel)
                         + vec3(rimLip * 0.06);

                // 轻度 Filmic 色调映射，保持色彩通透不发灰
                lit = lit / (lit + vec3(0.18)) * 1.14;
                fragColor = vec4(clamp(lit, 0.0, 1.0), 1.0);
            }
        """

        private const val SHADOW_VS = """#version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uMVP;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val SHADOW_FS = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                float r = length(vUv);
                float alpha = smoothstep(1.0, 0.15, r) * 0.18;
                fragColor = vec4(0.06, 0.09, 0.16, alpha);
            }
        """
    }
}
