package com.cfop.lab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class LgIcon {
    CUBE, ZOOM_OUT, ZOOM_IN, RESET, CAMERA, CLOSE,
    PLAY, PAUSE, PREV, NEXT, REWIND, INFO, CHECK
}

@Composable
fun GlassVectorIcon(
    icon: LgIcon,
    tint: Color = Color(0xFF18181B),
    size: Dp = 18.dp,
    strokeWidth: Float = 1.8f
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = strokeWidth * (w / 24f)
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x / 24f * w, y / 24f * h)

        when (icon) {
            LgIcon.CUBE -> {
                val hex = Path().apply {
                    moveTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.86f, h * 0.31f)
                    lineTo(w * 0.86f, h * 0.69f)
                    lineTo(w * 0.5f, h * 0.88f)
                    lineTo(w * 0.14f, h * 0.69f)
                    lineTo(w * 0.14f, h * 0.31f)
                    close()
                }
                drawPath(hex, tint, style = stroke)
                drawLine(tint, p(3.4f, 7.5f), p(12f, 12.2f), sw, StrokeCap.Round)
                drawLine(tint, p(20.6f, 7.5f), p(12f, 12.2f), sw, StrokeCap.Round)
                drawLine(tint, p(12f, 12.2f), p(12f, 21.1f), sw, StrokeCap.Round)
            }
            LgIcon.ZOOM_OUT -> {
                drawCircle(tint, radius = w * 0.31f, center = p(10.5f, 10.5f), style = stroke)
                drawLine(tint, p(16.2f, 16.2f), p(21f, 21f), sw, StrokeCap.Round)
                drawLine(tint, p(7.5f, 10.5f), p(13.5f, 10.5f), sw, StrokeCap.Round)
            }
            LgIcon.ZOOM_IN -> {
                drawCircle(tint, radius = w * 0.31f, center = p(10.5f, 10.5f), style = stroke)
                drawLine(tint, p(16.2f, 16.2f), p(21f, 21f), sw, StrokeCap.Round)
                drawLine(tint, p(7.5f, 10.5f), p(13.5f, 10.5f), sw, StrokeCap.Round)
                drawLine(tint, p(10.5f, 7.5f), p(10.5f, 13.5f), sw, StrokeCap.Round)
            }
            LgIcon.RESET -> {
                drawArc(
                    color = tint,
                    startAngle = -55f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = p(4f, 4f),
                    size = Size(w * 16f / 24f, h * 16f / 24f),
                    style = stroke
                )
                drawLine(tint, p(4f, 4f), p(4f, 9f), sw, StrokeCap.Round)
                drawLine(tint, p(4f, 9f), p(9f, 9f), sw, StrokeCap.Round)
            }
            LgIcon.CAMERA -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(2.5f, 6.5f),
                    size = Size(w * 19f / 24f, h * 13.5f / 24f),
                    cornerRadius = CornerRadius(w * 0.12f, h * 0.12f),
                    style = stroke
                )
                drawCircle(tint, radius = w * 0.16f, center = p(12f, 13.2f), style = stroke)
                drawLine(tint, p(8.5f, 6.5f), p(10f, 4f), sw, StrokeCap.Round)
                drawLine(tint, p(10f, 4f), p(14f, 4f), sw, StrokeCap.Round)
                drawLine(tint, p(14f, 4f), p(15.5f, 6.5f), sw, StrokeCap.Round)
            }
            LgIcon.CLOSE -> {
                drawLine(tint, p(6f, 6f), p(18f, 18f), sw, StrokeCap.Round)
                drawLine(tint, p(18f, 6f), p(6f, 18f), sw, StrokeCap.Round)
            }
            LgIcon.PLAY -> {
                val tri = Path().apply {
                    moveTo(w * 0.28f, h * 0.18f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.28f, h * 0.82f)
                    close()
                }
                drawPath(tri, tint)
            }
            LgIcon.PAUSE -> {
                drawRoundRect(tint, p(6f, 5f), Size(w * 4f / 24f, h * 14f / 24f), CornerRadius(3f, 3f))
                drawRoundRect(tint, p(14f, 5f), Size(w * 4f / 24f, h * 14f / 24f), CornerRadius(3f, 3f))
            }
            LgIcon.PREV -> {
                drawLine(tint, p(15f, 6f), p(9f, 12f), sw, StrokeCap.Round)
                drawLine(tint, p(9f, 12f), p(15f, 18f), sw, StrokeCap.Round)
            }
            LgIcon.NEXT -> {
                drawLine(tint, p(9f, 6f), p(15f, 12f), sw, StrokeCap.Round)
                drawLine(tint, p(15f, 12f), p(9f, 18f), sw, StrokeCap.Round)
            }
            LgIcon.REWIND -> {
                drawLine(tint, p(6f, 6f), p(6f, 18f), sw, StrokeCap.Round)
                val tri = Path().apply {
                    moveTo(w * 0.76f, h * 0.25f)
                    lineTo(w * 0.36f, h * 0.50f)
                    lineTo(w * 0.76f, h * 0.75f)
                    close()
                }
                drawPath(tri, tint, style = stroke)
            }
            LgIcon.INFO -> {
                drawCircle(tint, radius = w * 0.38f, center = p(12f, 12f), style = stroke)
                drawLine(tint, p(12f, 11f), p(12f, 16f), sw, StrokeCap.Round)
                drawCircle(tint, radius = sw * 0.55f, center = p(12f, 8f))
            }
            LgIcon.CHECK -> {
                drawLine(tint, p(5f, 12.5f), p(10f, 17.5f), sw, StrokeCap.Round)
                drawLine(tint, p(10f, 17.5f), p(19f, 7f), sw, StrokeCap.Round)
            }
        }
    }
}
