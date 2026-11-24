package com.akkos.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/** 58 mm a 203 dpi = 384 pontos de largura útil. */
const val PAPER_WIDTH = 384

/** Elementos que compõem um cupom. */
sealed class El {
    data class Txt(
        val text: String,
        val size: Float = 22f,
        val bold: Boolean = false,
        val align: Paint.Align = Paint.Align.LEFT,
    ) : El()

    /** Texto à esquerda e valor alinhado à direita na mesma linha. */
    data class Row(
        val left: String,
        val right: String,
        val size: Float = 22f,
        val bold: Boolean = false,
    ) : El()

    data object Line : El()
    data object DashLine : El()
    data class Space(val h: Int = 10) : El()

    /** Imagem já em preto e branco (QR, logo). Centralizada, sem reamostrar se couber. */
    class Img(val bmp: Bitmap) : El()
}

/**
 * Desenha o cupom num bitmap. O mesmo bitmap é usado na pré-visualização e
 * na impressão, então o que aparece na tela é exatamente o que sai no papel
 * (e acentos/ç funcionam sem depender da tabela de caracteres da impressora).
 */
object Renderer {
    private const val MARGIN = 4f

    fun render(els: List<El>): Bitmap {
        val h = draw(els, null).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(PAPER_WIDTH, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        draw(els, canvas)
        return bmp
    }

    private fun paint(size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split("\n")) {
            if (para.isBlank()) { out.add(""); continue }
            var line = ""
            for (word in para.split(" ")) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(cand) <= maxW) {
                    line = cand
                } else {
                    if (line.isNotEmpty()) out.add(line)
                    var w = word
                    while (p.measureText(w) > maxW) {
                        val n = p.breakText(w, true, maxW, null).coerceAtLeast(1)
                        out.add(w.substring(0, n))
                        w = w.substring(n)
                    }
                    line = w
                }
            }
            out.add(line)
        }
        return out
    }

    /** Desenha (ou só mede, se canvas == null) e devolve a altura total. */
    private fun draw(els: List<El>, c: Canvas?): Int {
        var y = 0f
        val right = PAPER_WIDTH - MARGIN
        val maxW = PAPER_WIDTH - 2 * MARGIN
        for (e in els) {
            when (e) {
                is El.Txt -> {
                    val p = paint(e.size, e.bold).apply { textAlign = e.align }
                    val x = when (e.align) {
                        Paint.Align.CENTER -> PAPER_WIDTH / 2f
                        Paint.Align.RIGHT -> right
                        else -> MARGIN
                    }
                    for (l in wrap(e.text, p, maxW)) {
                        c?.drawText(l, x, y - p.ascent(), p)
                        y += p.fontSpacing
                    }
                }
                is El.Row -> {
                    val p = paint(e.size, e.bold)
                    val rw = p.measureText(e.right)
                    val lines = wrap(e.left, p, (maxW - rw - 12f).coerceAtLeast(60f))
                    lines.forEachIndexed { i, l ->
                        c?.drawText(l, MARGIN, y - p.ascent(), p)
                        if (i == lines.lastIndex) {
                            p.textAlign = Paint.Align.RIGHT
                            c?.drawText(e.right, right, y - p.ascent(), p)
                            p.textAlign = Paint.Align.LEFT
                        }
                        y += p.fontSpacing
                    }
                }
                El.Line -> {
                    y += 6f
                    c?.drawRect(MARGIN, y, right, y + 2f, paint(1f, false))
                    y += 10f
                }
                El.DashLine -> {
                    y += 6f
                    val p = paint(1f, false)
                    var x = MARGIN
                    while (x < right) {
                        c?.drawRect(x, y, minOf(x + 8f, right), y + 2f, p)
                        x += 14f
                    }
                    y += 10f
                }
                is El.Space -> y += e.h
                is El.Img -> {
                    val w = minOf(e.bmp.width, PAPER_WIDTH)
                    val h = e.bmp.height * w / e.bmp.width
                    val left = (PAPER_WIDTH - w) / 2f
                    c?.drawBitmap(e.bmp, null, android.graphics.RectF(left, y, left + w, y + h), null)
                    y += h + 6
                }
            }
        }
        return y.toInt() + 4
    }
}

/** Converte o bitmap em comandos ESC/POS de imagem raster (GS v 0). */
object EscPos {
    fun fromBitmap(bmp: Bitmap, feedLines: Int = 4): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val wb = (w + 7) / 8
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @  (reset)

        // Envia em faixas pequenas: impressoras baratas têm buffer curto.
        val band = 64
        var y0 = 0
        while (y0 < h) {
            val bh = minOf(band, h - y0)
            out.write(
                byteArrayOf(
                    0x1D, 0x76, 0x30, 0x00,
                    (wb and 0xFF).toByte(), (wb shr 8).toByte(),
                    (bh and 0xFF).toByte(), (bh shr 8).toByte(),
                )
            )
            for (y in y0 until y0 + bh) {
                for (xb in 0 until wb) {
                    var b = 0
                    for (bit in 0..7) {
                        val x = xb * 8 + bit
                        if (x < w) {
                            val c = px[y * w + x]
                            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                            if (lum < 128) b = b or (0x80 shr bit)
                        }
                    }
                    out.write(b)
                }
            }
            y0 += bh
        }
        out.write(byteArrayOf(0x1B, 0x64, feedLines.toByte())) // avança papel
        return out.toByteArray()
    }
}
