package it.davide.parakeetdictation

import android.content.Context
import java.io.File

/** Immutable sherpa-onnx export of Parakeet TDT 0.6B v3, pinned by revision. */
object ModelFiles {
    const val REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    const val SAMPLE_RATE = 16_000
    const val TOTAL_BYTES = 670_478_772L

    data class Asset(
        val filename: String,
        val bytes: Long,
        val sha256: String,
    )

    val assets = listOf(
        Asset("encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        Asset("decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        Asset("joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        Asset("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )

    fun directory(context: Context): File = File(context.noBackupFilesDir, "models/parakeet-tdt-0.6b-v3-int8")

    fun file(context: Context, filename: String): File = File(directory(context), filename)

    fun isInstalled(context: Context): Boolean {
        return isInstalledPath(directory(context).absolutePath)
    }

    fun isInstalledPath(path: String?): Boolean {
        if (path == null) return false
        val directory = File(path)
        val marker = File(directory, ".complete")
        if (!marker.isFile || marker.readText().trim() != REVISION) return false
        return assets.all { asset ->
            File(directory, asset.filename).let { it.isFile && it.length() == asset.bytes }
        }
    }
}
