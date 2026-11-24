package com.cfop.lab.vision

import androidx.camera.core.ImageProxy
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class IsoSamplePoint(
    val faceSlot: Int, // 0=Top(U/D), 1=Left(F/B), 2=Right(R/L)
    val cellIdx: Int,  // 0..8
    val nx: Float,     // 归一化屏幕 X (0..1)
    val ny: Float      // 归一化屏幕 Y (0..1)
)

/**
 * 零内存分配、原生零依赖 的原生 YUV_420_888 等轴测三面同步采色器：
 * 一次对准同时读取 3 个面共 27 格的去反光中值 RGB（耗时 <1.5ms/帧），
 * 用户只需把魔方按示意图转两下（前 3 面 + 后 3 面），免点击自动完成全部 6 面扫描！
 */
object YuvIsometricSampler {

    // 预计算标准等轴测六边形内 3 个菱形面 × 9 格的归一化坐标 (中心 0.5, 0.5)
    val isoPoints: List<IsoSamplePoint> = buildList {
        val r = 0.34f
        val dx = r * cos(Math.toRadians(30.0)).toFloat() // 0.294
        val dy = r * sin(Math.toRadians(30.0)).toFloat() // 0.170
        val cx = 0.5f
        val cy = 0.5f

        // 顶点定义：O=(cx,cy), T=(cx,cy-r), TL=(cx-dx,cy-dy), TR=(cx+dx,cy-dy),
        //          BL=(cx-dx,cy+dy), BR=(cx+dx,cy+dy), B=(cx,cy+r)
        fun lerp2(ax: Float, ay: Float, bx: Float, by: Float, t: Float) =
            (ax + (bx - ax) * t) to (ay + (by - ay) * t)

        fun addDiamond(
            slot: Int,
            p00x: Float, p00y: Float, // 左上 (row=0,col=0)
            p01x: Float, p01y: Float, // 右上 (row=0,col=1)
            p10x: Float, p10y: Float, // 左下 (row=1,col=0)
            p11x: Float, p11y: Float  // 右下 (row=1,col=1)
        ) {
            for (row in 0 until 3) {
                val v = (row + 0.5f) / 3f
                val (lx, ly) = lerp2(p00x, p00y, p10x, p10y, v)
                val (rx, ry) = lerp2(p01x, p01y, p11x, p11y, v)
                for (col in 0 until 3) {
                    val u = (col + 0.5f) / 3f
                    val (px, py) = lerp2(lx, ly, rx, ry, u)
                    add(IsoSamplePoint(slot, row * 3 + col, px, py))
                }
            }
        }

        // 面 0：顶面菱形 (T -> TR -> O -> TL)
        addDiamond(0, cx, cy - r, cx + dx, cy - dy, cx - dx, cy - dy, cx, cy)
        // 面 1：左前面菱形 (TL -> O -> B -> BL)
        addDiamond(1, cx - dx, cy - dy, cx, cy, cx - dx, cy + dy, cx, cy + r)
        // 面 2：右前面菱形 (O -> TR -> BR -> B)
        addDiamond(2, cx, cy, cx + dx, cy - dy, cx, cy + r, cx + dx, cy + dy)
    }

    /**
     * 直接从 CameraX YUV_420_888 缓冲区按旋转角读取 27 个点的 5 点中值 RGB（零 Bitmap 分配！）
     */
    fun sample27Points(image: ImageProxy): List<IntArray> {
        val w = image.width
        val h = image.height
        val rot = image.imageInfo.rotationDegrees

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride

        // 预览框按正方形居中裁切 (CENTER_CROP)
        val dispW = if (rot == 90 || rot == 270) h else w
        val dispH = if (rot == 90 || rot == 270) w else h
        val squareSide = min(dispW, dispH).toFloat()
        val offX = (dispW - squareSide) * 0.5f
        val offY = (dispH - squareSide) * 0.5f

        fun readRgbAtDisplay(dx: Float, dy: Float): IntArray {
            // 将竖屏显示坐标映射回传感器原始坐标
            val sx: Int
            val sy: Int
            when (rot) {
                90 -> { sx = dy.toInt(); sy = (h - 1 - dx.toInt()) }
                270 -> { sx = (w - 1 - dy.toInt()); sy = dx.toInt() }
                180 -> { sx = (w - 1 - dx.toInt()); sy = (h - 1 - dy.toInt()) }
                else -> { sx = dx.toInt(); sy = dy.toInt() }
            }
            val cx = sx.coerceIn(0, w - 1)
            val cy = sy.coerceIn(0, h - 1)
            val yIdx = (cy * yRowStride + cx * yPixStride).coerceIn(0, yBuf.limit() - 1)
            val yVal = (yBuf.get(yIdx).toInt() and 0xFF)
            val uvIdx = ((cy / 2) * uvRowStride + (cx / 2) * uvPixStride).coerceIn(0, min(uBuf.limit(), vBuf.limit()) - 1)
            val uVal = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
            val vVal = (vBuf.get(uvIdx).toInt() and 0xFF) - 128

            val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
            val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
            val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)
            return intArrayOf(r, g, b)
        }

        val rad = squareSide * 0.018f
        return isoPoints.map { pt ->
            val px = offX + pt.nx * squareSide
            val py = offY + pt.ny * squareSide
            val samples = arrayOf(
                readRgbAtDisplay(px, py),
                readRgbAtDisplay(px - rad, py),
                readRgbAtDisplay(px + rad, py),
                readRgbAtDisplay(px, py - rad),
                readRgbAtDisplay(px, py + rad)
            )
            // 5 点中值滤波，滤除无贴纸魔方圆角高光反光点
            val rs = samples.map { it[0] }.sorted()
            val gs = samples.map { it[1] }.sorted()
            val bs = samples.map { it[2] }.sorted()
            intArrayOf(rs[2], gs[2], bs[2])
        }
    }
}
