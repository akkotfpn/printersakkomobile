package com.akkos.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Conexão Bluetooth clássica (SPP), que é o que essas impressoras de cupom usam. */
object Printer {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Dispositivos já pareados nas configurações do Android: (nome, endereço). */
    @SuppressLint("MissingPermission")
    fun paired(ctx: Context): List<Pair<String, String>> = try {
        adapter(ctx)?.bondedDevices
            ?.map { (it.name ?: it.address) to it.address }
            ?.sortedByDescending { looksLikePrinter(it.first) }
            ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun looksLikePrinter(name: String): Boolean {
        val n = name.lowercase()
        return listOf("print", "pt-", "mpt", "pos", "jp", "jingpu", "thermal", "58").any { it in n }
    }

    @SuppressLint("MissingPermission")
    suspend fun send(ctx: Context, address: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val ad = adapter(ctx) ?: error("Este aparelho não tem Bluetooth")
        if (!ad.isEnabled) error("Ligue o Bluetooth do celular")
        ad.cancelDiscovery()
        val dev = ad.getRemoteDevice(address)

        val socket: BluetoothSocket = try {
            dev.createRfcommSocketToServiceRecord(SPP).apply { connect() }
        } catch (e: IOException) {
            // Algumas impressoras chinesas só aceitam o canal 1 "na marra".
            try {
                val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(dev, 1) as BluetoothSocket).apply { connect() }
            } catch (e2: Exception) {
                throw IOException("Não conectou. A impressora está ligada e perto?")
            }
        }

        try {
            val out = socket.outputStream
            var i = 0
            while (i < data.size) {
                val n = minOf(256, data.size - i)
                out.write(data, i, n)
                out.flush()
                i += n
                Thread.sleep(12)
            }
            // Dá tempo da impressora esvaziar o buffer antes de fechar.
            Thread.sleep(800)
        } finally {
            runCatching { socket.close() }
        }
    }
}

fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

fun hasBtPermission(ctx: Context): Boolean = btPermissions().all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("akkos_print", Context.MODE_PRIVATE)

    var printer: String?
        get() = sp.getString("printer", null)
        set(v) { sp.edit().putString("printer", v).apply() }

    var nextNumber: Int
        get() = sp.getInt("seq", 1)
        set(v) { sp.edit().putInt("seq", v).apply() }

    var company: Company
        get() = Company(
            name = sp.getString("c_name", "") ?: "",
            doc = sp.getString("c_doc", "") ?: "",
            address = sp.getString("c_addr", "") ?: "",
            phone = sp.getString("c_phone", "") ?: "",
            footer = sp.getString("c_footer", "Obrigado pela preferência!") ?: "",
        )
        set(c) {
            sp.edit()
                .putString("c_name", c.name)
                .putString("c_doc", c.doc)
                .putString("c_addr", c.address)
                .putString("c_phone", c.phone)
                .putString("c_footer", c.footer)
                .apply()
        }
}
