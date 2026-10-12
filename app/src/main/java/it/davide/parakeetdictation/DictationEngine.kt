package it.davide.parakeetdictation

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide owner of the recognizer. Load, inference, and RAM release are serialized. */
object DictationEngine {
    enum class Phase { IDLE, MISSING_MODEL, LOADING, READY, RECORDING, TRANSCRIBING, ERROR }

    data class Status(val phase: Phase, val message: String, val acceleratorRequested: Boolean = false)

    fun interface Observer {
        fun onStatus(status: Status)
    }

    fun interface Completion {
        fun onComplete(text: String?, error: String?)
    }

    private const val RELEASE_DELAY_MS = 60_000L
    private const val PREFERENCES = "parakeet_dictation_settings"
    private const val KEEP_CPU_WARM_KEY = "keep_model_warm_on_cpu"
    private const val KEEP_NNAPI_WARM_KEY = "keep_model_warm_on_nnapi"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "parakeet-inference").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val observers = CopyOnWriteArrayList<Observer>()
    private val loadQueued = AtomicBoolean(false)

    @Volatile private var keyboardVisible = false
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var activeProvider = "cpu"
    @Volatile private var modelDirectory: String? = null
    @Volatile private var keepModelWarmOnCpu = false
    @Volatile private var keepModelWarmOnNnapi = false
    @Volatile private var nnapiFallbackActive = false
    @Volatile private var status = Status(Phase.MISSING_MODEL, "Scarica il modello nell'app")

    private val releaseRecognizer = Runnable {
        worker.execute {
            if (!keyboardVisible && !keepAnyModelWarm) {
                recognizer?.release()
                recognizer = null
                activeProvider = "cpu"
                nnapiFallbackActive = false
                publish(if (ModelFiles.isInstalledPath(modelDirectory)) {
                    Status(Phase.IDLE, "Modello scaricato dalla RAM")
                } else {
                    Status(Phase.MISSING_MODEL, "Scarica il modello nell'app")
                })
            }
        }
    }

    fun addObserver(observer: Observer) {
        observers.addIfAbsent(observer)
        mainHandler.post { observer.onStatus(status) }
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    fun currentPhase(): Phase = status.phase
    fun currentStatus(): Status = status

    fun isKeepModelWarmOnCpu(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEEP_CPU_WARM_KEY, false)

    fun isKeepModelWarmOnNnapi(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEEP_NNAPI_WARM_KEY, false)

    /** Restore the resident-CPU preference after the app or accessibility service process starts. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        keepModelWarmOnCpu = isKeepModelWarmOnCpu(appContext)
        keepModelWarmOnNnapi = isKeepModelWarmOnNnapi(appContext)
        modelDirectory = ModelFiles.directory(appContext).absolutePath
        if (!keepAnyModelWarm) return
        mainHandler.removeCallbacks(releaseRecognizer)
        if (!ModelFiles.isInstalled(appContext)) {
            publish(Status(Phase.MISSING_MODEL, "Scarica prima il modello nell'app"))
            return
        }
        val preferredProvider = preferredPersistentProvider()
        if (recognizer != null && (activeProvider == preferredProvider ||
                (keepModelWarmOnNnapi && nnapiFallbackActive))) {
            publish(readyStatus())
        } else {
            publish(loadingStatus(preferredProvider))
            enqueueLoad(appContext)
        }
    }

    /** When enabled, load on CPU immediately and keep the recognizer resident after the keyboard closes. */
    fun setKeepModelWarmOnCpu(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEEP_CPU_WARM_KEY, enabled)
            .putBoolean(KEEP_NNAPI_WARM_KEY, false)
            .apply()
        keepModelWarmOnCpu = enabled
        keepModelWarmOnNnapi = false
        modelDirectory = ModelFiles.directory(appContext).absolutePath
        mainHandler.removeCallbacks(releaseRecognizer)

        if (enabled) {
            if (!ModelFiles.isInstalled(appContext)) {
                publish(Status(Phase.MISSING_MODEL, "Scarica prima il modello nell'app"))
                return
            }
            if (recognizer != null && activeProvider == "cpu") {
                publish(readyStatus())
            } else {
                publish(loadingStatus("cpu"))
                enqueueLoad(appContext)
            }
        } else if (keyboardVisible) {
            if (recognizer != null) publish(readyStatus())
        } else {
            scheduleRecognizerRelease()
        }
    }

    /** Keep the NNAPI recognizer resident; Android's driver chooses any supported accelerator. */
    fun setKeepModelWarmOnNnapi(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEEP_NNAPI_WARM_KEY, enabled)
            .putBoolean(KEEP_CPU_WARM_KEY, false)
            .apply()
        keepModelWarmOnCpu = false
        keepModelWarmOnNnapi = enabled
        modelDirectory = ModelFiles.directory(appContext).absolutePath
        mainHandler.removeCallbacks(releaseRecognizer)

        if (enabled) {
            if (!ModelFiles.isInstalled(appContext)) {
                publish(Status(Phase.MISSING_MODEL, "Scarica prima il modello nell'app"))
                return
            }
            if (recognizer != null && (activeProvider == "nnapi" || nnapiFallbackActive)) {
                publish(readyStatus())
            } else {
                publish(loadingStatus("nnapi"))
                enqueueLoad(appContext)
            }
        } else if (keyboardVisible) {
            if (recognizer != null) publish(readyStatus())
        } else {
            scheduleRecognizerRelease()
        }
    }

    fun onKeyboardShown(context: Context) {
        mainHandler.removeCallbacks(releaseRecognizer)
        keyboardVisible = true
        keepModelWarmOnCpu = isKeepModelWarmOnCpu(context)
        keepModelWarmOnNnapi = isKeepModelWarmOnNnapi(context)
        modelDirectory = ModelFiles.directory(context).absolutePath
        if (!ModelFiles.isInstalled(context)) {
            publish(Status(Phase.MISSING_MODEL, "Scarica prima il modello nell'app"))
            return
        }
        if (recognizer != null && (!keepModelWarmOnCpu || activeProvider == "cpu") &&
            (!keepModelWarmOnNnapi || activeProvider == "nnapi" || nnapiFallbackActive)) {
            publish(readyStatus())
            return
        }
        publish(loadingStatus(preferredPersistentProvider()))
        enqueueLoad(context.applicationContext)
    }

    /** Hide the bubble immediately; retain the recognizer for one minute to absorb IME flaps. */
    fun onKeyboardHidden() {
        keyboardVisible = false
        mainHandler.removeCallbacks(releaseRecognizer)
        if (keepAnyModelWarm) {
            if (recognizer != null) publish(readyStatus())
        } else {
            scheduleRecognizerRelease()
        }
    }

    fun onRecordingStarted() {
        publish(Status(Phase.RECORDING, "Sto ascoltando…"))
    }

    fun onRecordingStopped() {
        publish(Status(Phase.TRANSCRIBING, "Trascrivo in italiano…"))
    }

    fun cancelRecordingStatus() {
        publish(if (keyboardVisible && recognizer != null) readyStatus() else Status(Phase.IDLE, "In attesa della tastiera"))
    }

    fun transcribe(samples: FloatArray, completion: Completion) {
        if (samples.size < ModelFiles.SAMPLE_RATE / 4) {
            mainHandler.post { completion.onComplete(null, "Dettatura troppo breve." ) }
            if (keyboardVisible && recognizer != null) publish(readyStatus())
            return
        }
        publish(Status(Phase.TRANSCRIBING, "Trascrivo in italiano…"))
        worker.execute {
            try {
                var current = recognizer ?: error("Il modello non è caricato.")
                val text = try {
                    decode(current, samples)
                } catch (acceleratorError: Throwable) {
                    if (activeProvider != "nnapi") throw acceleratorError
                    current.release()
                    recognizer = null
                    current = createRecognizer("cpu")
                    activeProvider = "cpu"
                    nnapiFallbackActive = true
                    recognizer = current
                    decode(current, samples)
                }
                if (keyboardVisible) publish(readyStatus())
                mainHandler.post { completion.onComplete(text, null) }
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Trascrizione non riuscita."
                if (keyboardVisible) publish(Status(Phase.ERROR, message))
                mainHandler.post { completion.onComplete(null, message) }
            }
        }
    }

    private fun enqueueLoad(appContext: Context) {
        if (!loadQueued.compareAndSet(false, true)) return
        worker.execute {
            val preferredProvider = preferredPersistentProvider()
            try {
                if (!keyboardVisible && !keepAnyModelWarm) return@execute
                if (recognizer != null && activeProvider != preferredProvider &&
                    !(keepModelWarmOnNnapi && nnapiFallbackActive)) {
                    recognizer?.release()
                    recognizer = null
                }
                if (recognizer != null) {
                    publish(readyStatus())
                    return@execute
                }
                val memory = ActivityManager.MemoryInfo()
                (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
                if (memory.lowMemory) {
                    publish(Status(Phase.ERROR, "Il telefono segnala poca memoria libera."))
                    return@execute
                }
                if (preferredProvider == "cpu") {
                    tryLoad(appContext, "cpu")
                } else {
                    tryLoad(appContext, "nnapi")
                }
            } catch (acceleratorError: Throwable) {
                if (preferredProvider == "cpu") {
                    if (keyboardVisible || keepAnyModelWarm) {
                        val details = acceleratorError.message?.takeIf { it.isNotBlank() }
                            ?: "Errore nel caricamento del modello sulla CPU."
                        publish(Status(Phase.ERROR, "Caricamento non riuscito: $details"))
                    }
                    return@execute
                }
                if (!keyboardVisible && !keepAnyModelWarm) return@execute
                try {
                    val loaded = createRecognizer("cpu")
                    if (keyboardVisible || keepAnyModelWarm) {
                        recognizer = loaded
                        activeProvider = "cpu"
                        nnapiFallbackActive = true
                        publish(readyStatus())
                    } else {
                        loaded.release()
                    }
                } catch (cpuError: Throwable) {
                    val details = cpuError.message?.takeIf { it.isNotBlank() }
                        ?: acceleratorError.message?.takeIf { it.isNotBlank() }
                        ?: "Errore nel caricamento del modello."
                    if (keyboardVisible) publish(Status(Phase.ERROR, "Caricamento non riuscito: $details"))
                }
            } finally {
                loadQueued.set(false)
                val shouldRemainLoaded = keyboardVisible || keepAnyModelWarm
                val providerNeedsSwitch = recognizer != null && (
                    (keepModelWarmOnCpu && activeProvider != "cpu") ||
                        (keepModelWarmOnNnapi && activeProvider != "nnapi" && !nnapiFallbackActive)
                    )
                val loadWasInterrupted = shouldRemainLoaded && recognizer == null && status.phase == Phase.LOADING
                if (shouldRemainLoaded && ModelFiles.isInstalledPath(modelDirectory) && (providerNeedsSwitch || loadWasInterrupted)) {
                    if (providerNeedsSwitch) publish(loadingStatus(preferredPersistentProvider()))
                    enqueueLoad(appContext)
                }
            }
        }
    }

    private fun tryLoad(context: Context, provider: String) {
        val loaded = createRecognizer(provider)
        if (keyboardVisible || keepAnyModelWarm) {
            recognizer = loaded
            activeProvider = provider
            nnapiFallbackActive = false
            publish(readyStatus())
        } else {
            loaded.release()
        }
    }

    private val keepAnyModelWarm: Boolean
        get() = keepModelWarmOnCpu || keepModelWarmOnNnapi

    private fun preferredPersistentProvider(): String = if (keepModelWarmOnCpu) "cpu" else "nnapi"

    private fun loadingStatus(provider: String): Status = if (provider == "cpu") {
        Status(Phase.LOADING, "Carico Parakeet sulla CPU…")
    } else {
        Status(Phase.LOADING, "Carico Parakeet con NNAPI…")
    }

    private fun scheduleRecognizerRelease() {
        publish(if (ModelFiles.isInstalledPath(modelDirectory)) {
            Status(Phase.IDLE, "Modello in RAM per 1 minuto")
        } else {
            Status(Phase.MISSING_MODEL, "Scarica prima il modello nell'app")
        })
        mainHandler.postDelayed(releaseRecognizer, RELEASE_DELAY_MS)
    }

    private fun createRecognizer(provider: String): OfflineRecognizer {
        val directory = modelDirectory ?: error("Cartella del modello non disponibile.")
        val model = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$directory/encoder.int8.onnx",
                decoder = "$directory/decoder.int8.onnx",
                joiner = "$directory/joiner.int8.onnx",
            ),
            numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6),
            provider = provider,
            modelType = "nemo_transducer",
            tokens = "$directory/tokens.txt",
        )
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = ModelFiles.SAMPLE_RATE, featureDim = 80, dither = 0.0f),
                modelConfig = model,
            )
        )
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, ModelFiles.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun readyStatus(): Status = when {
        activeProvider == "nnapi" -> Status(Phase.READY, "Pronto · NNAPI gestita dal driver", acceleratorRequested = true)
        nnapiFallbackActive -> Status(Phase.READY, "Pronto · CPU (fallback NNAPI)", acceleratorRequested = false)
        else -> Status(Phase.READY, "Pronto · CPU", acceleratorRequested = false)
    }

    private fun publish(next: Status) {
        status = next
        observers.forEach { observer -> mainHandler.post { observer.onStatus(next) } }
    }
}
