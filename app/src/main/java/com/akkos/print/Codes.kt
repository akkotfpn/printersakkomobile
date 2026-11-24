package com.akkos.print

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.Normalizer
import java.util.Locale

object Codes {
    /** QR Code nítido: cada módulo vira um quadrado inteiro de pixels. */
    fun qr(text: String, targetPx: Int = 300): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val module = (targetPx / m.width).coerceAtLeast(1)
        val size = m.width * module
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            px[y * size + x] = if (m.get(x / module, y / module)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun ascii(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^A-Za-z0-9 .,\\-]"), "").trim()

    private fun f(id: String, v: String) = id + "%02d".format(v.length) + v

    /** Normaliza a chave: telefone ganha +55, CPF/CNPJ fica só com dígitos. */
    fun normalizeKey(raw: String): String {
        val k = raw.trim()
        if (k.contains("@")) return k.lowercase()
        if (Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(k)) return k.lowercase()
        if (k.startsWith("+")) return "+" + k.filter { it.isDigit() }
        val d = k.filter { it.isDigit() }
        // 11 dígitos = CPF, 14 = CNPJ. Telefone deve vir com +55 (ex.: +5512991234567).
        return when (d.length) {
            11, 14 -> d
            else -> k
        }
    }

    /** Payload "Pix copia e cola" (BR Code estático) com CRC16. */
    fun pix(key: String, name: String, city: String, amount: Double, txid: String): String {
        val gui = f("00", "br.gov.bcb.pix") + f("01", normalizeKey(key))
        val nm = ascii(name).ifBlank { "RECEBEDOR" }.take(25)
        val ct = ascii(city).ifBlank { "BRASIL" }.take(15)
        val tx = txid.filter { it.isLetterOrDigit() }.take(25).ifBlank { "***" }
        val sb = StringBuilder()
            .append(f("00", "01"))
            .append(f("26", gui))
            .append(f("52", "0000"))
            .append(f("53", "986"))
        if (amount > 0) sb.append(f("54", String.format(Locale.US, "%.2f", amount)))
        sb.append(f("58", "BR"))
            .append(f("59", nm))
            .append(f("60", ct))
            .append(f("62", f("05", tx)))
            .append("6304")
        val p = sb.toString()
        return p + crc16(p)
    }

    private fun crc16(s: String): String {
        var crc = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return "%04X".format(crc)
    }
}
