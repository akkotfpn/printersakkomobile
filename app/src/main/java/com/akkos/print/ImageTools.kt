package com.akkos.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build

/** Arquivo recebido pelo menu "Compartilhar" ou "Abrir com". */
data class SharedFile(val uri: Uri, val mime: String?)

/** O que foi carregado: uma foto (1 página) ou as páginas de um PDF. */
class Source(val name: String, val pages: List<Bitmap>, val isPdf: Boolean, val totalPages: Int)

object ImageTools {
    private const val MAX_PDF_PAGES = 30
    private const val MAX_SIDE = 2000

    fun isPdf(ctx: Context, uri: Uri, mime: String?): Boolean {
        val m = mime ?: ctx.contentResolver.getType(uri)
        return m == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")
    }

    fun load(ctx: Context, uri: Uri, mime: String?): Source {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
        return if (isPdf(ctx, uri, mime)) {
            val (pages, total) = loadPdf(ctx, uri)
            Source(name, pages, true, total)
        } else {
            Source(name, listOf(loadImage(ctx, uri)), false, 1)
        }
    }

    private fun loadImage(ctx: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            // ImageDecoder já respeita a rotação EXIF das fotos do celular.
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val big = maxOf(w, h)
                if (big > MAX_SIDE) {
                    val f = MAX_SIDE.toFloat() / big
                    decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Não consegui abrir a imagem")
    }

    private fun loadPdf(ctx: Context, uri: Uri): Pair<List<Bitmap>, Int> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("Não consegui abrir o PDF")
        return pfd.use {
            PdfRenderer(it).use { r ->
                val total = r.pageCount
                val pages = (0 until minOf(total, MAX_PDF_PAGES)).map { i ->
                    r.openPage(i).use { p ->
                        val w = 1000
                        val h = (w * p.height / p.width.toFloat()).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        b
                    }
                }
                pages to total
            }
        }
    }

    /**
     * Gira (opcional), reduz para a largura do papel e converte em preto e branco.
     * dither = true: pontilhado Floyd–Steinberg (bom para fotos).
     * dither = false: corte simples (bom para texto, código de barras e QR).
     * bright: -100..100, clareia/escurece antes de converter.
     */
    fun prepare(src: Bitmap, rotate: Boolean, dither: Boolean, bright: Int): Bitmap {
        val rotated = if (rotate) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(90f) }, true)
        } else src

        val w = PAPER_WIDTH
        val h = (rotated.height * w / rotated.width.toFloat()).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            drawColor(Color.WHITE) // fundo branco para PNG transparente
            drawBitmap(rotated, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }

        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h) { i ->
            val c = px[i]
            val g = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            (g + bright * 1.28f).coerceIn(0f, 255f)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = gray[i]
                val new = if (old < 128f) 0f else 255f
                px[i] = if (new == 0f) Color.BLACK else Color.WHITE
                if (dither) {
                    val err = old - new
                    if (x + 1 < w) gray[i + 1] += err * 7 / 16
                    if (y + 1 < h) {
                        if (x > 0) gray[i + w - 1] += err * 3 / 16
                        gray[i + w] += err * 5 / 16
                        if (x + 1 < w) gray[i + w + 1] += err * 1 / 16
                    }
                }
            }
        }
        scaled.setPixels(px, 0, w, 0, 0, w, h)
        return scaled
    }

    /** "1-3, 5" -> [0,1,2,4]. Vazio = todas. */
    fun parsePages(text: String, count: Int): List<Int> {
        if (text.isBlank()) return (0 until count).toList()
        val out = sortedSetOf<Int>()
        text.split(",", ";", " ").filter { it.isNotBlank() }.forEach { part ->
            val bits = part.split("-")
            val a = bits[0].trim().toIntOrNull() ?: return@forEach
            val b = bits.getOrNull(1)?.trim()?.toIntOrNull() ?: a
            for (p in minOf(a, b)..maxOf(a, b)) if (p in 1..count) out.add(p - 1)
        }
        return out.toList()
    }
}
