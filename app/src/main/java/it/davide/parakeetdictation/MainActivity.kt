package it.davide.parakeetdictation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var modelLabel: TextView
    private lateinit var downloadButton: Button
    private lateinit var downloadProgress: ProgressBar
    private lateinit var permissionButton: Button
    private lateinit var serviceButton: Button
    private lateinit var serviceStatus: TextView
    private lateinit var keepCpuSwitch: Switch
    private lateinit var keepCpuDescription: TextView
    private var downloadActive = false
    private var updatingCpuSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 19, 26)
        window.navigationBarColor = Color.rgb(16, 19, 26)
        window.decorView.systemUiVisibility = 0
        setContentView(buildScreen())
        DictationEngine.initialize(this)
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        DictationEngine.initialize(this)
        refreshState()
    }

    @Deprecated("Deprecated in Android 13 in favor of Activity Result APIs; retained for minSdk 26 compatibility.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) refreshState()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 19, 26))
            isFillViewport = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(30))
        }
        scroll.addView(page)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandMark = TextView(this).apply {
            text = "●"
            textSize = 19f
            setTextColor(LIME)
            gravity = Gravity.CENTER
            background = rounded(LIME.withAlpha(34), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }
        brandRow.addView(brandMark)
        brandRow.addView(label("PARAKEET  /  VOCE LOCALE", 12f, MUTED, true).apply {
            letterSpacing = 0.12f
            setPadding(dp(12), 0, 0, 0)
        })
        page.addView(brandRow)

        page.addView(label("Detta\novunque.", 38f, WHITE, true).apply {
            setPadding(0, dp(26), 0, dp(8))
            setLineSpacing(0f, 0.94f)
        })
        page.addView(label("Una bolla vocale sopra la tastiera che usi già, pronta appena compare.", 16f, MUTED).apply {
            setPadding(0, 0, 0, dp(22))
        })

        val modelCard = card()
        modelCard.addView(label("MODELLO", 11f, MUTED, true).apply { letterSpacing = 0.12f })
        modelCard.addView(label("Parakeet TDT v3 · 0.6B", 19f, WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        modelLabel = label("Modello non ancora scaricato", 14f, MUTED)
        modelCard.addView(modelLabel)
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(LIME)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(49, 54, 66))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(5)).apply {
                topMargin = dp(14)
            }
        }
        modelCard.addView(downloadProgress)
        downloadButton = actionButton("Scarica il modello · 671 MB", primary = true).apply {
            setOnClickListener { beginDownload() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(18) }
        }
        modelCard.addView(downloadButton)
        val cpuOption = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        val cpuOptionText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        cpuOptionText.addView(label("Mantieni il modello in RAM · CPU", 14f, WHITE, true))
        keepCpuDescription = label("Disattivo: viene scaricato un minuto dopo la chiusura della tastiera.", 12f, MUTED).apply {
            setPadding(0, dp(4), dp(8), 0)
        }
        cpuOptionText.addView(keepCpuDescription)
        cpuOption.addView(cpuOptionText)
        keepCpuSwitch = Switch(this).apply {
            isChecked = DictationEngine.isKeepModelWarmOnCpu(this@MainActivity)
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(LIME, Color.rgb(210, 214, 222)),
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(LIME.withAlpha(100), Color.rgb(77, 83, 96)),
            )
            setOnCheckedChangeListener { _, checked ->
                if (updatingCpuSwitch) return@setOnCheckedChangeListener
                if (checked && !ModelFiles.isInstalled(this@MainActivity)) {
                    updatingCpuSwitch = true
                    isChecked = false
                    updatingCpuSwitch = false
                    return@setOnCheckedChangeListener
                }
                updateCpuOptionDescription(checked)
                DictationEngine.setKeepModelWarmOnCpu(this@MainActivity, checked)
            }
        }
        cpuOption.addView(keepCpuSwitch)
        modelCard.addView(cpuOption)
        page.addView(modelCard)

        val setupCard = card().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        setupCard.addView(label("ATTIVAZIONE", 11f, MUTED, true).apply { letterSpacing = 0.12f })
        setupCard.addView(label("Collega la bolla", 20f, WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(5))
        })
        setupCard.addView(label("Non sostituisce la tua tastiera. Il servizio di accessibilità rileva quando compare e inserisce nel campo attivo solo il testo che detti.", 14f, MUTED))
        permissionButton = actionButton("Autorizza il microfono", primary = false).apply {
            setOnClickListener { requestAudioPermission() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(16) }
        }
        setupCard.addView(permissionButton)
        serviceStatus = label("Servizio di accessibilità non attivo", 13f, MUTED).apply {
            setPadding(0, dp(14), 0, dp(7))
        }
        setupCard.addView(serviceStatus)
        serviceButton = actionButton("Attiva bolla fluttuante", primary = true).apply {
            setOnClickListener { explainAccessibilityAndOpenSettings() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
        }
        setupCard.addView(serviceButton)
        page.addView(setupCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val howCard = card()
        howCard.addView(label("COME FUNZIONA", 11f, MUTED, true).apply { letterSpacing = 0.12f })
        howCard.addView(step("01", "Concedi microfono e attiva il servizio di accessibilità."))
        howCard.addView(step("02", "Apri un campo di testo: la bolla appare e il modello si carica subito."))
        howCard.addView(step("03", "Tocca la bolla, parla e toccala di nuovo: il testo entra nel campo attivo."))
        howCard.addView(step("04", "La bolla verde è trascinabile. Quando chiudi la tastiera sparisce; il modello viene scaricato dopo 1 minuto, a meno che tu attivi la modalità CPU sempre in RAM."))
        page.addView(howCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val noteCard = card().apply { setBackground(rounded(Color.rgb(28, 32, 42), dp(20))) }
        noteCard.addView(label("PRIVATO SUL DISPOSITIVO", 11f, LIME, true).apply { letterSpacing = 0.1f })
        noteCard.addView(label("L'audio non viene inviato al cloud. Il modello ONNX è circa 671 MB e il testo viene prodotto quando interrompi la dettatura.", 14f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        noteCard.addView(label("Accelerazione Android NNAPI preferita; l'eventuale GPU o NPU dipende dal driver. Se non disponibile, viene usata la CPU.", 13f, MUTED))
        page.addView(noteCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val demoCard = card()
        demoCard.addView(label("PROVA LA BOLLA", 11f, MUTED, true).apply { letterSpacing = 0.12f })
        val demoField = EditText(this).apply {
            hint = "Tocca qui: resta la tua tastiera abituale…"
            setHintTextColor(MUTED)
            setTextColor(WHITE)
            textSize = 16f
            setSingleLine(false)
            minLines = 2
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(31, 36, 47), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        demoCard.addView(demoField)
        page.addView(demoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        page.addView(label("Parakeet TDT 0.6B v3 · sherpa-onnx · Italiano", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(4))
        })
        return scroll
    }

    private fun beginDownload() {
        if (ModelDownloader.isDownloading() || ModelFiles.isInstalled(this)) return
        downloadActive = true
        downloadProgress.visibility = View.VISIBLE
        downloadButton.isEnabled = false
        downloadButton.text = "Preparazione download…"
        ModelDownloader.start(this, object : ModelDownloader.Listener {
            override fun onProgress(progress: ModelDownloader.Progress) {
                runOnUiThread {
                    downloadProgress.progress = ((progress.downloadedBytes * 1000) / progress.totalBytes).toInt().coerceIn(0, 1000)
                    modelLabel.text = "${progress.message} · ${formatMb(progress.downloadedBytes)} / ${formatMb(progress.totalBytes)}"
                    downloadButton.text = "Download in corso…"
                }
            }

            override fun onComplete() {
                runOnUiThread {
                    downloadActive = false
                    downloadProgress.visibility = View.GONE
                    DictationEngine.initialize(this@MainActivity)
                    refreshState()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    downloadActive = false
                    downloadButton.isEnabled = true
                    downloadButton.text = "Riprendi il download"
                    modelLabel.text = message
                    downloadProgress.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun requestAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            refreshState()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
    }

    private fun explainAccessibilityAndOpenSettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Attiva la bolla fluttuante")
            .setMessage("Parakeet usa il servizio di accessibilità per rilevare la tastiera e inserire nel campo attivo il testo che detti. Non legge né invia il contenuto degli altri campi. La registrazione parte solo quando tocchi la bolla.")
            .setNegativeButton("Indietro", null)
            .setPositiveButton("Apri impostazioni") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun refreshState() {
        if (!::modelLabel.isInitialized) return
        val modelReady = ModelFiles.isInstalled(this)
        updatingCpuSwitch = true
        keepCpuSwitch.isChecked = DictationEngine.isKeepModelWarmOnCpu(this)
        keepCpuSwitch.isEnabled = modelReady
        updateCpuOptionDescription(keepCpuSwitch.isChecked)
        updatingCpuSwitch = false
        if (!downloadActive && !ModelDownloader.isDownloading()) {
            modelLabel.text = if (modelReady) "Modello installato e verificato · 671 MB" else "Modello non ancora scaricato · 671 MB"
            downloadButton.text = if (modelReady) "Modello verificato" else "Scarica il modello · 671 MB"
            downloadButton.isEnabled = !modelReady
            downloadProgress.visibility = View.GONE
        }

        val permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        permissionButton.text = if (permissionGranted) "Microfono autorizzato" else "Autorizza il microfono"
        permissionButton.isEnabled = !permissionGranted

        val enabled = isAccessibilityServiceEnabled()
        serviceStatus.text = when {
            enabled -> "Bolla fluttuante attiva"
            else -> "Servizio di accessibilità non attivo"
        }
        serviceButton.text = if (enabled) "Impostazioni accessibilità" else "Attiva bolla fluttuante"
        serviceButton.isEnabled = permissionGranted
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = android.content.ComponentName(this, DictationAccessibilityService::class.java)
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName && info.resolveInfo.serviceInfo.name == expected.className
        }
    }

    private fun step(number: String, description: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        val count = label(number, 12f, LIME, true).apply {
            setPadding(0, dp(14), dp(12), 0)
        }
        addView(count)
        addView(label(description, 14f, WHITE).apply {
            setPadding(0, dp(12), 0, dp(9))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(CARD, dp(20))
    }

    private fun actionButton(text: String, primary: Boolean): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.rgb(20, 26, 18) else WHITE)
        background = rounded(if (primary) LIME else Color.rgb(47, 53, 66), dp(16))
        stateListAnimator = null
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun formatMb(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateCpuOptionDescription(enabled: Boolean) {
        keepCpuDescription.text = if (enabled) {
            "Attivo: il modello resta nella RAM sulla CPU anche quando la tastiera è chiusa. Aumenta l'uso di memoria e batteria."
        } else {
            "Disattivo: viene scaricato un minuto dopo la chiusura della tastiera."
        }
    }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00ffffff) or (alpha shl 24)

    companion object {
        private const val REQUEST_AUDIO = 41
        private const val WHITE = Color.WHITE
        private const val MUTED = 0xffa2aabb.toInt()
        private const val LIME = 0xffb8f36b.toInt()
        private const val CARD = 0xff1b202a.toInt()
    }
}

