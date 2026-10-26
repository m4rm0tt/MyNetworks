package ch.m4rm0tt.mynetworks.ui.components

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pictogrammes dessinés localement (aucune ressource distante, aucune police d'icônes). */
enum class Glyph { Search, Settings, Plus, Star, StarOutline, Phone, Chat, Edit, Info, Close, Mail, Link, Trash, Person, Company, Up, Down, Lock, Check }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint) }
}

fun starPath(cx: Float, cy: Float, outer: Float, inner: Float = outer * 0.48f): Path {
    val p = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / 5
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

fun DrawScope.drawGlyph(glyph: Glyph, tint: Color) {
    val w = size.width
    val s = w / 24f
    val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (glyph) {
        Glyph.Search -> {
            drawCircle(tint, 7f * s, p(10.5f, 10.5f), style = stroke)
            drawLine(tint, p(15.8f, 15.8f), p(20.5f, 20.5f), 2.4f * s, StrokeCap.Round)
        }
        Glyph.Settings -> {
            drawCircle(tint, 3.2f * s, p(12f, 12f), style = stroke)
            for (i in 0 until 8) {
                val a = i * PI / 4
                val c = cos(a).toFloat(); val n = sin(a).toFloat()
                drawLine(tint, p(12f + 6.2f * c, 12f + 6.2f * n), p(12f + 9f * c, 12f + 9f * n), 2.4f * s, StrokeCap.Round)
            }
            drawCircle(tint, 6.6f * s, p(12f, 12f), style = Stroke(1.6f * s))
        }
        Glyph.Plus -> {
            drawLine(tint, p(12f, 5f), p(12f, 19f), 2.6f * s, StrokeCap.Round)
            drawLine(tint, p(5f, 12f), p(19f, 12f), 2.6f * s, StrokeCap.Round)
        }
        Glyph.Star -> drawPath(starPath(12f * s, 12.6f * s, 9.5f * s), tint)
        Glyph.StarOutline -> drawPath(starPath(12f * s, 12.6f * s, 9.5f * s), tint, style = stroke)
        Glyph.Phone -> {
            val path = Path().apply {
                moveTo(6.5f * s, 3.5f * s); lineTo(9.5f * s, 3.5f * s); lineTo(11f * s, 8f * s); lineTo(8.8f * s, 9.6f * s)
                cubicTo(9.9f * s, 12f * s, 12f * s, 14.1f * s, 14.4f * s, 15.2f * s)
                lineTo(16f * s, 13f * s); lineTo(20.5f * s, 14.5f * s); lineTo(20.5f * s, 17.5f * s)
                cubicTo(20.5f * s, 19.2f * s, 19.2f * s, 20.5f * s, 17.5f * s, 20.5f * s)
                cubicTo(10f * s, 20.5f * s, 3.5f * s, 14f * s, 3.5f * s, 6.5f * s)
                cubicTo(3.5f * s, 4.8f * s, 4.8f * s, 3.5f * s, 6.5f * s, 3.5f * s)
                close()
            }
            drawPath(path, tint, style = stroke)
        }
        Glyph.Chat -> {
            drawCircle(tint, 8.5f * s, p(12f, 11.5f), style = stroke)
            val tail = Path().apply { moveTo(6.2f * s, 17.6f * s); lineTo(4f * s, 21f * s); lineTo(8.6f * s, 19.4f * s) }
            drawPath(tail, tint, style = stroke)
        }
        Glyph.Edit -> {
            drawLine(tint, p(5f, 19f), p(17f, 7f), 2.4f * s, StrokeCap.Round)
            drawLine(tint, p(15f, 5f), p(19f, 9f), 2.4f * s, StrokeCap.Round)
            drawLine(tint, p(4.5f, 19.5f), p(8.5f, 19.5f), 2f * s, StrokeCap.Round)
        }
        Glyph.Info -> {
            drawCircle(tint, 9f * s, p(12f, 12f), style = stroke)
            drawCircle(tint, 1.3f * s, p(12f, 7.8f))
            drawLine(tint, p(12f, 11f), p(12f, 16.5f), 2.2f * s, StrokeCap.Round)
        }
        Glyph.Close -> {
            drawLine(tint, p(6.5f, 6.5f), p(17.5f, 17.5f), 2.4f * s, StrokeCap.Round)
            drawLine(tint, p(17.5f, 6.5f), p(6.5f, 17.5f), 2.4f * s, StrokeCap.Round)
        }
        Glyph.Mail -> {
            drawRoundRect(tint, p(3.5f, 5.5f), Size(17f * s, 13f * s), CornerRadius(3f * s), style = stroke)
            val v = Path().apply { moveTo(4.5f * s, 7f * s); lineTo(12f * s, 12.5f * s); lineTo(19.5f * s, 7f * s) }
            drawPath(v, tint, style = stroke)
        }
        Glyph.Link -> {
            drawRoundRect(tint, p(2.5f, 8.5f), Size(11f * s, 7f * s), CornerRadius(3.5f * s), style = stroke)
            drawRoundRect(tint, p(10.5f, 8.5f), Size(11f * s, 7f * s), CornerRadius(3.5f * s), style = stroke)
        }
        Glyph.Trash -> {
            drawLine(tint, p(4f, 6.5f), p(20f, 6.5f), 2.2f * s, StrokeCap.Round)
            drawLine(tint, p(9.5f, 3.8f), p(14.5f, 3.8f), 2.2f * s, StrokeCap.Round)
            val body = Path().apply { moveTo(6f * s, 6.5f * s); lineTo(7.2f * s, 20f * s); lineTo(16.8f * s, 20f * s); lineTo(18f * s, 6.5f * s) }
            drawPath(body, tint, style = stroke)
        }
        Glyph.Person -> {
            drawCircle(tint, 4f * s, p(12f, 8f), style = stroke)
            val b = Path().apply { moveTo(4.5f * s, 20f * s); cubicTo(5.5f * s, 14f * s, 18.5f * s, 14f * s, 19.5f * s, 20f * s) }
            drawPath(b, tint, style = stroke)
        }
        Glyph.Company -> {
            drawRoundRect(tint, p(4f, 4f), Size(16f * s, 16f * s), CornerRadius(3f * s), style = stroke)
            for (y in listOf(8.5f, 12f, 15.5f)) {
                drawLine(tint, p(8f, y), p(9.5f, y), 2f * s, StrokeCap.Round)
                drawLine(tint, p(14.5f, y), p(16f, y), 2f * s, StrokeCap.Round)
            }
        }
        Glyph.Up -> {
            val v = Path().apply { moveTo(6f * s, 15f * s); lineTo(12f * s, 9f * s); lineTo(18f * s, 15f * s) }
            drawPath(v, tint, style = stroke)
        }
        Glyph.Down -> {
            val v = Path().apply { moveTo(6f * s, 9f * s); lineTo(12f * s, 15f * s); lineTo(18f * s, 9f * s) }
            drawPath(v, tint, style = stroke)
        }
        Glyph.Lock -> {
            drawRoundRect(tint, p(5f, 10.5f), Size(14f * s, 10f * s), CornerRadius(2.5f * s), style = stroke)
            val arc = Path().apply { moveTo(8f * s, 10.5f * s); lineTo(8f * s, 8f * s); cubicTo(8f * s, 2.8f * s, 16f * s, 2.8f * s, 16f * s, 8f * s); lineTo(16f * s, 10.5f * s) }
            drawPath(arc, tint, style = stroke)
        }
        Glyph.Check -> {
            val v = Path().apply { moveTo(5f * s, 12.5f * s); lineTo(10f * s, 17.5f * s); lineTo(19f * s, 7f * s) }
            drawPath(v, tint, style = Stroke(2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
