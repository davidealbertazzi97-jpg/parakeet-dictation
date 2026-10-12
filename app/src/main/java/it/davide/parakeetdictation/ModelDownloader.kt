package it.davide.parakeetdictation

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object ModelDownloader {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long, val message: String)

    interface Listener {
        fun onProgress(progress: Progress)
        fun onComplete()
        fun onError(message: String)
    }

    private val running = AtomicBoolean(false)

    fun start(context: Context, listener: Listener) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            try {
                downloadModel(appContext, listener)
                listener.onComplete()
            } catch (error: Throwable) {
                listener.onError(error.message ?: "Download interrotto. Riprova con una connessione stabile.")
            } finally {
                running.set(false)
            }
        }, "parakeet-model-download").start()
    }

    fun isDownloading(): Boolean = running.get()

    private fun downloadModel(context: Context, listener: Listener) {
        val directory = ModelFiles.directory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            error("Impossibile creare la cartella privata del modello.")
        }
        val stat = StatFs(directory.absolutePath)
        val requiredFree = ModelFiles.TOTAL_BYTES + 128L * 1024 * 1024
        if (stat.availableBytes < requiredFree) {
            error("Servono almeno 800 MB liberi nella memoria interna del telefono.")
        }

        var completeBytes = 0L
        ModelFiles.assets.forEach { asset ->
            val destination = File(directory, asset.filename)
            if (destination.isFile && destination.length() == asset.bytes && sha256(destination) == asset.sha256) {
                completeBytes += asset.bytes
                listener.onProgress(Progress(completeBytes, ModelFiles.TOTAL_BYTES, "Modello già verificato"))
                return@forEach
            }

            destination.delete()
            val part = File(directory, "${asset.filename}.part")
            if (part.length() > asset.bytes) part.delete()
            downloadAsset(asset, part) { received ->
                listener.onProgress(
                    Progress(
                        completeBytes + received,
                        ModelFiles.TOTAL_BYTES,
                        "Scarico ${asset.filename.removeSuffix(".int8.onnx").removeSuffix(".txt")}"
                    )
                )
            }
            if (part.length() != asset.bytes) error("Dimensione non valida per ${asset.filename}.")
            if (sha256(part) != asset.sha256) {
                part.delete()
                error("Verifica SHA-256 non riuscita per ${asset.filename}; il file è stato scartato.")
            }
            if (!part.renameTo(destination)) error("Impossibile salvare ${asset.filename}.")
            completeBytes += asset.bytes
        }

        val marker = File(directory, ".complete.tmp")
        marker.writeText(ModelFiles.REVISION)
        if (!marker.renameTo(File(directory, ".complete"))) {
            marker.delete()
            error("Impossibile completare l'installazione del modello.")
        }
    }

    private fun downloadAsset(asset: ModelFiles.Asset, part: File, onBytes: (Long) -> Unit) {
        val url = URL(
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/" +
                "${ModelFiles.REVISION}/${asset.filename}?download=true"
        )
        var offset = part.length()
        var connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "ParakeetDictation/0.1 (Android)")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }

        var code = connection.responseCode
        if (offset > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            part.delete()
            offset = 0L
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "ParakeetDictation/0.1 (Android)")
            }
            code = connection.responseCode
        }

        if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect()
            error("Hugging Face ha risposto HTTP $code.")
        }

        val append = offset > 0 && code == HttpURLConnection.HTTP_PARTIAL
        var received = if (append) offset else 0L
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(part, append)).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        if (received > asset.bytes) error("Il server ha inviato più dati del previsto per ${asset.filename}.")
                        onBytes(received)
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
