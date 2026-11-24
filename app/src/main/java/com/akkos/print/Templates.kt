package com.akkos.print

import android.graphics.Bitmap
import android.graphics.Paint
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Item(val desc: String = "", val qty: String = "1", val price: String = "")

data class Field(
    val key: String,
    val label: String,
    val multiline: Boolean = false,
    val numeric: Boolean = false,
    val default: String = "",
)

data class Company(
    val name: String = "",
    val doc: String = "",
    val address: String = "",
    val phone: String = "",
    val footer: String = "Obrigado pela preferência!",
    val pixKey: String = "",
    val pixCity: String = "",
    val logo: Bitmap? = null,
)

class Data(val v: Map<String, String>, val items: List<Item>, val company: Company, val number: Int) {
    fun s(k: String): String = v[k]?.trim().orEmpty()
    val wantsPix: Boolean get() = v[PIX_FLAG] == "1" && company.pixKey.isNotBlank()
}

/** Chave interna usada pelo editor para ligar/desligar o QR do Pix. */
const val PIX_FLAG = "__pix"

class Template(
    val name: String,
    val description: String,
    val fields: List<Field>,
    val hasItems: Boolean,
    val numbered: Boolean,
    val pix: Boolean = false,
    val build: (Data) -> List<El>,
)

private val BR = Locale("pt", "BR")
private val CENTER = Paint.Align.CENTER

fun money(d: Double): String = NumberFormat.getCurrencyInstance(BR).format(d)

/** Aceita "10,50", "1.234,56", "10.5", "R$ 7". */
fun num(s: String): Double {
    val t = s.replace("R$", "").replace(" ", "").trim()
    val n = if (t.contains(",")) t.replace(".", "").replace(",", ".") else t
    return n.toDoubleOrNull() ?: 0.0
}

private fun now(): String = SimpleDateFormat("dd/MM/yyyy HH:mm", BR).format(Date())
private fun today(): String = SimpleDateFormat("d 'de' MMMM 'de' yyyy", BR).format(Date())
private fun qtyText(q: Double) = if (q == Math.floor(q)) q.toLong().toString() else String.format(BR, "%.2f", q)

// ---------- blocos reutilizáveis ----------

private fun header(d: Data, title: String): MutableList<El> {
    val out = mutableListOf<El>()
    val c = d.company
    c.logo?.let { out.add(El.Img(it)) }
    if (c.name.isNotBlank()) out.add(El.Txt(c.name, size = 28f, bold = true, align = CENTER))
    if (c.doc.isNotBlank()) out.add(El.Txt(c.doc, size = 18f, align = CENTER))
    if (c.address.isNotBlank()) out.add(El.Txt(c.address, size = 18f, align = CENTER))
    if (c.phone.isNotBlank()) out.add(El.Txt("Tel: ${c.phone}", size = 18f, align = CENTER))
    if (c.name.isNotBlank() || c.logo != null) out.add(El.Line)
    out.add(El.Txt(title, size = 26f, bold = true, align = CENTER))
    out.add(El.Txt(if (d.number > 0) "Nº %06d   %s".format(d.number, now()) else now(), size = 18f, align = CENTER))
    out.add(El.Line)
    return out
}

private fun info(out: MutableList<El>, label: String, value: String) {
    if (value.isNotBlank()) out.add(El.Txt("$label: $value", size = 20f))
}

private fun block(out: MutableList<El>, label: String, value: String) {
    if (value.isBlank()) return
    out.add(El.Txt(label, size = 18f, bold = true))
    out.add(El.Txt(value, size = 22f))
    out.add(El.Space(4))
}

/** Lista os itens e devolve o total. */
private fun items(d: Data, out: MutableList<El>, title: String = "ITEM"): Double {
    val valid = d.items.filter { it.desc.isNotBlank() }
    if (valid.isEmpty()) return 0.0
    var total = 0.0
    out.add(El.Row(title, "VALOR", size = 18f, bold = true))
    valid.forEach { item ->
        val q = num(item.qty).let { if (it <= 0) 1.0 else it }
        val p = num(item.price)
        val t = q * p
        total += t
        out.add(El.Txt(item.desc, size = 22f))
        out.add(El.Row("   ${qtyText(q)} x ${money(p)}", money(t), size = 20f))
    }
    out.add(El.DashLine)
    return total
}

private fun pixBlock(d: Data, out: MutableList<El>, amount: Double) {
    if (!d.wantsPix || amount <= 0) return
    val code = Codes.pix(d.company.pixKey, d.company.name, d.company.pixCity, amount, "AKKO%06d".format(d.number))
    out.add(El.Space(10))
    out.add(El.Txt("Pague com Pix", size = 22f, bold = true, align = CENTER))
    out.add(El.Img(Codes.qr(code, 260)))
}

private fun signature(out: MutableList<El>, name: String) {
    out.add(El.Space(50))
    out.add(El.Txt("______________________________", size = 20f, align = CENTER))
    out.add(El.Txt(name.ifBlank { "Assinatura" }, size = 18f, align = CENTER))
}

private fun footer(d: Data, out: MutableList<El>) {
    out.add(El.Space(12))
    if (d.company.footer.isNotBlank()) out.add(El.Txt(d.company.footer, size = 20f, align = CENTER))
    out.add(El.Txt("DOCUMENTO SEM VALOR FISCAL", size = 16f, bold = true, align = CENTER))
}

// ---------- modelos ----------

val TEMPLATES: List<Template> = listOf(

    Template(
        name = "Nota de venda",
        description = "Itens, desconto, total e forma de pagamento",
        fields = listOf(
            Field("cliente", "Cliente"),
            Field("doc", "CPF/CNPJ do cliente"),
            Field("desconto", "Desconto (R$)", numeric = true),
            Field("pagamento", "Forma de pagamento", default = "Pix"),
            Field("obs", "Observações", multiline = true),
        ),
        hasItems = true,
        numbered = true,
        pix = true,
    ) { d ->
        val out = header(d, "NOTA DE VENDA")
        info(out, "Cliente", d.s("cliente"))
        info(out, "CPF/CNPJ", d.s("doc"))
        if (d.s("cliente").isNotEmpty() || d.s("doc").isNotEmpty()) out.add(El.DashLine)
        val sub = items(d, out)
        val desc = num(d.s("desconto"))
        if (desc > 0) {
            out.add(El.Row("Subtotal", money(sub), size = 20f))
            out.add(El.Row("Desconto", "- " + money(desc), size = 20f))
        }
        out.add(El.Row("TOTAL", money((sub - desc).coerceAtLeast(0.0)), size = 30f, bold = true))
        if (d.s("pagamento").isNotEmpty()) out.add(El.Row("Pagamento", d.s("pagamento"), size = 20f))
        if (d.s("obs").isNotEmpty()) { out.add(El.Space(8)); out.add(El.Txt("Obs: ${d.s("obs")}", size = 18f)) }
        pixBlock(d, out, (sub - desc).coerceAtLeast(0.0))
        footer(d, out)
        out
    },

    Template(
        name = "Recibo",
        description = "Recibo de pagamento com valor por extenso e assinatura",
        fields = listOf(
            Field("de", "Recebi de"),
            Field("doc", "CPF/CNPJ de quem pagou"),
            Field("valor", "Valor (R$)", numeric = true),
            Field("ref", "Referente a", multiline = true),
            Field("pagamento", "Forma de pagamento", default = "Pix"),
            Field("cidade", "Cidade", default = "Caçapava"),
        ),
        hasItems = false,
        numbered = true,
        pix = true,
    ) { d ->
        val out = header(d, "RECIBO")
        val v = num(d.s("valor"))
        out.add(El.Row("VALOR", money(v), size = 30f, bold = true))
        out.add(El.DashLine)
        val doc = if (d.s("doc").isNotEmpty()) ", CPF/CNPJ ${d.s("doc")}," else ""
        val ref = if (d.s("ref").isNotEmpty()) ", referente a ${d.s("ref")}" else ""
        out.add(
            El.Txt(
                "Recebi de ${d.s("de").ifBlank { "________________" }}$doc a importância de " +
                    "${money(v)} (${extenso(v)})$ref.",
                size = 22f,
            )
        )
        if (d.s("pagamento").isNotEmpty()) { out.add(El.Space(8)); info(out, "Forma de pagamento", d.s("pagamento")) }
        out.add(El.Space(10))
        out.add(El.Txt(if (d.s("cidade").isNotEmpty()) "${d.s("cidade")}, ${today()}" else today(), size = 20f))
        signature(out, d.company.name)
        pixBlock(d, out, v)
        out
    },

    Template(
        name = "Orçamento",
        description = "Itens, total, validade e prazo",
        fields = listOf(
            Field("cliente", "Cliente"),
            Field("tel", "Telefone do cliente"),
            Field("validade", "Validade (dias)", numeric = true, default = "7"),
            Field("prazo", "Prazo de entrega"),
            Field("obs", "Observações", multiline = true),
        ),
        hasItems = true,
        numbered = true,
        pix = true,
    ) { d ->
        val out = header(d, "ORÇAMENTO")
        info(out, "Cliente", d.s("cliente"))
        info(out, "Telefone", d.s("tel"))
        if (d.s("cliente").isNotEmpty() || d.s("tel").isNotEmpty()) out.add(El.DashLine)
        val total = items(d, out)
        out.add(El.Row("TOTAL", money(total), size = 30f, bold = true))
        out.add(El.Space(6))
        if (d.s("validade").isNotEmpty()) info(out, "Válido por", "${d.s("validade")} dias")
        info(out, "Prazo de entrega", d.s("prazo"))
        if (d.s("obs").isNotEmpty()) out.add(El.Txt("Obs: ${d.s("obs")}", size = 18f))
        pixBlock(d, out, total)
        footer(d, out)
        out
    },

    Template(
        name = "Ordem de serviço",
        description = "Equipamento, defeito, serviço, peças e garantia",
        fields = listOf(
            Field("cliente", "Cliente"),
            Field("tel", "Telefone do cliente"),
            Field("equip", "Equipamento"),
            Field("defeito", "Defeito relatado", multiline = true),
            Field("servico", "Serviço realizado", multiline = true),
            Field("garantia", "Garantia", default = "90 dias"),
        ),
        hasItems = true,
        numbered = true,
    ) { d ->
        val out = header(d, "ORDEM DE SERVIÇO")
        info(out, "Cliente", d.s("cliente"))
        info(out, "Telefone", d.s("tel"))
        out.add(El.DashLine)
        block(out, "EQUIPAMENTO", d.s("equip"))
        block(out, "DEFEITO RELATADO", d.s("defeito"))
        block(out, "SERVIÇO REALIZADO", d.s("servico"))
        out.add(El.DashLine)
        val total = items(d, out, "PEÇAS / SERVIÇOS")
        if (total > 0) out.add(El.Row("TOTAL", money(total), size = 30f, bold = true))
        info(out, "Garantia", d.s("garantia"))
        signature(out, "Cliente")
        footer(d, out)
        out
    },

    Template(
        name = "Cobrança Pix",
        description = "QR Code do Pix com valor, pra cliente pagar na hora",
        fields = listOf(
            Field("valor", "Valor (R$)", numeric = true),
            Field("desc", "Descrição (opcional)"),
        ),
        hasItems = false,
        numbered = true,
    ) { d ->
        val out = mutableListOf<El>()
        val c = d.company
        val v = num(d.s("valor"))
        out.add(El.Txt("PIX", size = 40f, bold = true, align = CENTER))
        if (c.name.isNotBlank()) out.add(El.Txt(c.name, size = 22f, align = CENTER))
        out.add(El.Line)
        if (c.pixKey.isBlank()) {
            out.add(El.Txt("Cadastre a chave Pix em Dados da empresa.", size = 22f, bold = true, align = CENTER))
        } else {
            val code = Codes.pix(c.pixKey, c.name, c.pixCity, v, "AKKO%06d".format(d.number))
            out.add(El.Img(Codes.qr(code, 340)))
            if (v > 0) out.add(El.Txt(money(v), size = 36f, bold = true, align = CENTER))
            if (d.s("desc").isNotEmpty()) out.add(El.Txt(d.s("desc"), size = 22f, align = CENTER))
            out.add(El.Space(6))
            out.add(El.Txt("Chave: ${c.pixKey}", size = 18f, align = CENTER))
            out.add(El.Txt(now(), size = 16f, align = CENTER))
        }
        out
    },

    Template(
        name = "QR Code",
        description = "Link, Wi-Fi, contato ou qualquer texto em QR",
        fields = listOf(
            Field("texto", "Link ou texto", multiline = true),
            Field("legenda", "Legenda embaixo (opcional)"),
            Field("wifi_senha", "Senha do Wi-Fi (só se o texto for o nome da rede)"),
        ),
        hasItems = false,
        numbered = false,
    ) { d ->
        val out = mutableListOf<El>()
        val raw = d.s("texto")
        if (raw.isEmpty()) {
            out.add(El.Txt("Digite um link ou texto", size = 22f, align = CENTER))
        } else {
            val content = if (d.s("wifi_senha").isNotEmpty()) {
                fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")
                "WIFI:T:WPA;S:${esc(raw)};P:${esc(d.s("wifi_senha"))};;"
            } else raw
            out.add(El.Img(Codes.qr(content, 340)))
            if (d.s("legenda").isNotEmpty()) out.add(El.Txt(d.s("legenda"), size = 24f, bold = true, align = CENTER))
            if (d.s("wifi_senha").isNotEmpty()) out.add(El.Txt("Wi-Fi: $raw", size = 20f, align = CENTER))
        }
        out
    },

    Template(
        name = "Texto livre",
        description = "Qualquer texto, do tamanho que quiser",
        fields = listOf(
            Field("titulo", "Título (opcional)"),
            Field("texto", "Texto", multiline = true),
            Field("tamanho", "Tamanho da letra (14 a 60)", numeric = true, default = "24"),
        ),
        hasItems = false,
        numbered = false,
    ) { d ->
        val out = mutableListOf<El>()
        if (d.s("titulo").isNotEmpty()) {
            out.add(El.Txt(d.s("titulo"), size = 32f, bold = true, align = CENTER))
            out.add(El.Line)
        }
        out.add(El.Txt(d.s("texto"), size = num(d.s("tamanho")).toFloat().coerceIn(14f, 60f)))
        out
    },
)

fun testPage(): List<El> = listOf(
    El.Txt("Akko's Print", size = 32f, bold = true, align = CENTER),
    El.Txt("Impressora conectada!", size = 22f, align = CENTER),
    El.Line,
    El.Txt("Acentos: ação, coração, pão, é, ü, ç", size = 20f),
    El.Row("Esquerda", "Direita", size = 22f),
    El.Row("TOTAL", money(1234.56), size = 30f, bold = true),
    El.DashLine,
    El.Txt(now(), size = 18f, align = CENTER),
)

// ---------- valor por extenso ----------

private val UNIDADES = listOf(
    "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez",
    "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
)
private val DEZENAS = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
private val CENTENAS = listOf(
    "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos",
    "seiscentos", "setecentos", "oitocentos", "novecentos",
)

private fun ate999(n: Int): String {
    if (n == 100) return "cem"
    val parts = mutableListOf<String>()
    val c = n / 100
    val r = n % 100
    if (c > 0) parts.add(CENTENAS[c])
    if (r > 0) parts.add(if (r < 20) UNIDADES[r] else DEZENAS[r / 10] + if (r % 10 > 0) " e " + UNIDADES[r % 10] else "")
    return parts.joinToString(" e ")
}

private fun inteiro(n: Long): String {
    if (n == 0L) return "zero"
    val mi = (n / 1_000_000).toInt()
    val mil = ((n / 1000) % 1000).toInt()
    val resto = (n % 1000).toInt()
    val parts = mutableListOf<String>()
    if (mi > 0) parts.add(ate999(mi) + if (mi == 1) " milhão" else " milhões")
    if (mil > 0) parts.add(if (mil == 1) "mil" else ate999(mil) + " mil")
    if (resto > 0) parts.add(ate999(resto))
    if (parts.size == 1) return parts[0]
    val last = parts.removeAt(parts.lastIndex)
    val sep = if (resto > 0 && (resto < 100 || resto % 100 == 0)) " e " else " "
    return parts.joinToString(", ") + sep + last
}

fun extenso(v: Double): String {
    val cents = Math.round(v * 100)
    val reais = cents / 100
    val c = (cents % 100).toInt()
    val parts = mutableListOf<String>()
    if (reais > 0) {
        val redondo = reais % 1_000_000 == 0L
        parts.add(inteiro(reais) + when {
            reais == 1L -> " real"
            redondo -> " de reais"
            else -> " reais"
        })
    }
    if (c > 0) parts.add(ate999(c) + if (c == 1) " centavo" else " centavos")
    return if (parts.isEmpty()) "zero reais" else parts.joinToString(" e ")
}
