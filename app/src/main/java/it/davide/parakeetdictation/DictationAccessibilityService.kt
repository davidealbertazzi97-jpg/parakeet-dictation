package it.davide.parakeetdictation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import it.davide.parakeetdictation.DictationEngine.Completion

/** Floating push-to-talk bubble tied to the visibility of the user's existing soft keyboard. */
class DictationAccessibilityService : AccessibilityService(), DictationEngine.Observer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var micButton: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var keyboardVisible = false
    private var keyboardTop = 0
    private var sensitiveEditor = false
    private var editorAvailable = false
    @Volatile private var recording = false
    @Volatile private var submitAfterRecording = false
    @Volatile private var audioRecord: AudioRecord? = null
    private var recorderThread: Thread? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragWindowStartX = 0
    private var dragWindowStartY = 0
    private var bubbleDragging = false

    private val keyboardCheck = Runnable { updateKeyboardVisibility() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        DictationEngine.addObserver(this)
        DictationEngine.initialize(this)
        scheduleKeyboardCheck(250)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            sensitiveEditor = runCatching { event.source?.let(::isPasswordNode) ?: false }.getOrDefault(false)
        }
        scheduleKeyboardCheck(120)
    }

    override fun onInterrupt() {
        if (keyboardVisible) setKeyboardVisible(false, keyboardTop)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(keyboardCheck)
        stopRecording(submit = false)
        hideBubble()
        DictationEngine.removeObserver(this)
        DictationEngine.onKeyboardHidden()
        super.onDestroy()
    }

    override fun onStatus(status: DictationEngine.Status) {
        if (!keyboardVisible) return
        renderStatus(status)
    }

    private fun scheduleKeyboardCheck(delayMs: Long) {
        mainHandler.removeCallbacks(keyboardCheck)
        mainHandler.postDelayed(keyboardCheck, delayMs)
    }

    private fun updateKeyboardVisibility() {
        val inputMethodWindow = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrNull()
        val bounds = Rect()
        inputMethodWindow?.getBoundsInScreen(bounds)
        val visible = inputMethodWindow != null && bounds.height() >= dp(100) && bounds.width() >= dp(160)
        if (visible) {
            val editor = runCatching { focusedEditableNode() }.getOrNull()
            editorAvailable = editor?.isEditable == true
            sensitiveEditor = editor?.let(::isPasswordNode) == true
            setKeyboardVisible(true, bounds.top)
        } else if (keyboardVisible) {
            setKeyboardVisible(false, keyboardTop)
        }
    }

    private fun setKeyboardVisible(visible: Boolean, top: Int) {
        val changed = keyboardVisible != visible
        keyboardVisible = visible
        keyboardTop = top
        if (visible) {
            if (bubble == null) showBubble()
            else updateBubblePosition()
            if (changed) DictationEngine.onKeyboardShown(this)
            refreshSensitiveState()
            if (changed) {
                mainHandler.postDelayed({ if (keyboardVisible) refreshSensitiveState() }, 250)
                mainHandler.postDelayed({ if (keyboardVisible) refreshSensitiveState() }, 700)
            }
        } else {
            hideBubble()
            if (recording) stopRecording(submit = false)
            if (changed) DictationEngine.onKeyboardHidden()
        }
    }

    private fun showBubble() {
        val diameter = dp(BUBBLE_DIAMETER_DP)
        val mic = FrameLayout(this).apply {
            background = circle(LIME)
            isClickable = true
            isFocusable = false
            contentDescription = "Avvia dettatura Parakeet"
            addView(MicGlyph(this@DictationAccessibilityService), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { onMicTapped() }
            setOnTouchListener(::handleBubbleTouch)
        }
        micButton = mic

        val point = screenSize()
        val prefs = getSharedPreferences(BUBBLE_PREFERENCES, MODE_PRIVATE)
        val maxX = maxOf(0, point.x - diameter)
        val maxY = maxOf(0, point.y - diameter)
        val hasSavedPosition = prefs.contains(BUBBLE_X_FRACTION) && prefs.contains(BUBBLE_Y_FRACTION)
        val params = WindowManager.LayoutParams(
            diameter,
            diameter,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (hasSavedPosition) (prefs.getFloat(BUBBLE_X_FRACTION, 1f) * maxX).toInt() else maxX - dp(14)
            y = if (hasSavedPosition) (prefs.getFloat(BUBBLE_Y_FRACTION, 0.5f) * maxY).toInt() else keyboardTop + dp(10)
            title = "Parakeet Dictation"
        }
        clampBubblePosition(params, point.x, point.y, diameter)
        try {
            windowManager.addView(mic, params)
            bubble = mic
            windowParams = params
            renderStatus(DictationEngine.currentStatus())
        } catch (_: SecurityException) {
            bubble = null
            openSetup()
        } catch (_: WindowManager.BadTokenException) {
            bubble = null
        }
    }

    private fun updateBubblePosition() {
        val view = bubble ?: return
        val params = windowParams ?: return
        val point = screenSize()
        clampBubblePosition(params, point.x, point.y, view.width.takeIf { it > 0 } ?: dp(BUBBLE_DIAMETER_DP))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun hideBubble() {
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        micButton = null
        windowParams = null
    }

    private fun handleBubbleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val params = windowParams ?: return false
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragWindowStartX = params.x
                dragWindowStartY = params.y
                bubbleDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragStartX
                val deltaY = event.rawY - dragStartY
                if (!bubbleDragging && (kotlin.math.abs(deltaX) > ViewConfiguration.get(this).scaledTouchSlop ||
                        kotlin.math.abs(deltaY) > ViewConfiguration.get(this).scaledTouchSlop)
                ) bubbleDragging = true
                if (bubbleDragging) moveBubbleTo(dragWindowStartX + deltaX.toInt(), dragWindowStartY + deltaY.toInt())
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (bubbleDragging) saveBubblePosition() else view.performClick()
                bubbleDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                bubbleDragging = false
                return true
            }
        }
        return false
    }

    private fun moveBubbleTo(x: Int, y: Int) {
        val view = bubble ?: return
        val params = windowParams ?: return
        val point = screenSize()
        clampBubblePosition(params, point.x, point.y, view.width.takeIf { it > 0 } ?: dp(BUBBLE_DIAMETER_DP))
        val size = view.width.takeIf { it > 0 } ?: dp(BUBBLE_DIAMETER_DP)
        params.x = x.coerceIn(0, maxOf(0, point.x - size))
        params.y = y.coerceIn(0, maxOf(0, point.y - size))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun saveBubblePosition() {
        val view = bubble ?: return
        val params = windowParams ?: return
        val point = screenSize()
        val size = view.width.takeIf { it > 0 } ?: dp(BUBBLE_DIAMETER_DP)
        val maxX = maxOf(1, point.x - size)
        val maxY = maxOf(1, point.y - size)
        getSharedPreferences(BUBBLE_PREFERENCES, MODE_PRIVATE).edit()
            .putFloat(BUBBLE_X_FRACTION, params.x.toFloat() / maxX)
            .putFloat(BUBBLE_Y_FRACTION, params.y.toFloat() / maxY)
            .apply()
    }

    private fun screenSize(): Point = Point().also { point ->
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
    }

    private fun clampBubblePosition(params: WindowManager.LayoutParams, screenWidth: Int, screenHeight: Int, diameter: Int) {
        params.x = params.x.coerceIn(0, maxOf(0, screenWidth - diameter))
        params.y = params.y.coerceIn(0, maxOf(0, screenHeight - diameter))
    }

    private fun renderStatus(status: DictationEngine.Status) {
        val isRecording = status.phase == DictationEngine.Phase.RECORDING
        val isBusy = status.phase == DictationEngine.Phase.LOADING || status.phase == DictationEngine.Phase.TRANSCRIBING
        val phaseTitle = when (status.phase) {
            DictationEngine.Phase.MISSING_MODEL -> "Scarica il modello"
            DictationEngine.Phase.LOADING -> "Carico Parakeet…"
            DictationEngine.Phase.READY -> if (status.acceleratorRequested) "Pronto · NNAPI" else "Pronto · CPU"
            DictationEngine.Phase.RECORDING -> "Sto ascoltando…"
            DictationEngine.Phase.TRANSCRIBING -> "Trascrivo…"
            DictationEngine.Phase.ERROR -> "Tocca per riprovare"
            DictationEngine.Phase.IDLE -> "Preparo Parakeet…"
        }
        micButton?.let { button ->
            button.isEnabled = true
            button.alpha = 1f
            button.background = circle(LIME)
            val actionDescription = when {
                sensitiveEditor -> "Dettatura disattivata nel campo protetto"
                !editorAvailable -> "Dettatura Parakeet. Campo di testo non disponibile. $phaseTitle"
                isBusy && !isRecording -> "Dettatura Parakeet. $phaseTitle"
                isRecording -> "Interrompi e trascrivi"
                else -> "Avvia dettatura. $phaseTitle"
            }
            button.contentDescription = "Parakeet Dictation. ${status.message}. $actionDescription"
        }
        // Keep status available to accessibility users without adding text around the green circle.
    }

    private fun refreshSensitiveState() {
        val node = runCatching { focusedEditableNode() }.getOrNull()
        editorAvailable = node?.isEditable == true
        sensitiveEditor = node?.let(::isPasswordNode) == true
        renderStatus(
            DictationEngine.currentStatus()
        )
    }

    private fun onMicTapped() {
        if (!keyboardVisible) return
        if (recording) {
            stopRecording(submit = true)
            return
        }
        if (sensitiveEditor || focusedEditableNode()?.let(::isPasswordNode) == true) {
            sensitiveEditor = true
            renderStatus(DictationEngine.Status(DictationEngine.Phase.ERROR, "Campo protetto"))
            return
        }
        if (focusedEditableNode()?.isEditable != true) {
            renderError("Campo di testo non accessibile")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openSetup()
            return
        }
        when (DictationEngine.currentPhase()) {
            DictationEngine.Phase.MISSING_MODEL, DictationEngine.Phase.ERROR, DictationEngine.Phase.IDLE -> openSetup()
            DictationEngine.Phase.READY -> startRecording()
            else -> Unit
        }
    }

    private fun startRecording() {
        if (recording || !keyboardVisible || sensitiveEditor) return
        val minimum = AudioRecord.getMinBufferSize(
            ModelFiles.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            renderError("Microfono non disponibile su questo dispositivo.")
            return
        }
        val bufferBytes = maxOf(minimum * 2, 4096)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                ModelFiles.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (error: Throwable) {
            renderError(error.message ?: "Impossibile aprire il microfono.")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            renderError("Impossibile inizializzare il microfono.")
            return
        }
        recording = true
        submitAfterRecording = false
        audioRecord = recorder
        DictationEngine.onRecordingStarted()
        recorderThread = Thread({ capture(recorder, bufferBytes / 2) }, "parakeet-audio-capture").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun capture(recorder: AudioRecord, bufferSamples: Int) {
        val samples = FloatSamples(MAX_SAMPLES)
        val buffer = ShortArray(maxOf(bufferSamples, 1024))
        try {
            recorder.startRecording()
            while (recording && samples.size < MAX_SAMPLES) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) samples.append(buffer, count)
                if (count < 0 && recording) error("Lettura dal microfono interrotta ($count).")
            }
            if (samples.size >= MAX_SAMPLES) submitAfterRecording = true
        } catch (error: Throwable) {
            if (recording) {
                mainHandler.post { renderError(error.message ?: "Errore durante la registrazione.") }
                submitAfterRecording = false
            }
        } finally {
            recording = false
            audioRecord = null
            try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: IllegalStateException) { }
            recorder.release()
        }

        if (submitAfterRecording && keyboardVisible && !sensitiveEditor) {
            DictationEngine.onRecordingStopped()
            DictationEngine.transcribe(samples.toArray(), object : Completion {
                override fun onComplete(text: String?, error: String?) {
                    if (!keyboardVisible || sensitiveEditor) return
                    when {
                        !text.isNullOrBlank() -> {
                            val inserted = insertIntoFocusedField(text)
                            renderResult(if (inserted) "Testo inserito" else "Campo non compatibile")
                        }
                        error != null -> renderError(error)
                        else -> renderResult("Non ho riconosciuto parole")
                    }
                }
            })
        } else {
            DictationEngine.cancelRecordingStatus()
        }
    }

    private fun stopRecording(submit: Boolean) {
        if (!recording) return
        submitAfterRecording = submit
        recording = false
        try { audioRecord?.stop() } catch (_: IllegalStateException) { }
        if (submit) DictationEngine.onRecordingStopped() else DictationEngine.cancelRecordingStatus()
    }

    private fun insertIntoFocusedField(text: String): Boolean {
        val node = runCatching { focusedEditableNode() }.getOrNull() ?: return false
        if (!node.isEditable || isPasswordNode(node)) return false

        val existing = node.text?.toString().orEmpty()
        if (existing.length > MAX_EDITABLE_TEXT) return false
        val selectionStart = node.textSelectionStart.takeIf { it >= 0 }?.coerceIn(0, existing.length) ?: existing.length
        val selectionEnd = node.textSelectionEnd.takeIf { it >= 0 }?.coerceIn(0, existing.length) ?: selectionStart
        val lower = minOf(selectionStart, selectionEnd)
        val upper = maxOf(selectionStart, selectionEnd)
        val prefix = existing.substring(0, lower)
        val suffix = existing.substring(upper)
        val core = text.trim()
        val beforeSpace = prefix.lastOrNull()?.isLetterOrDigit() == true && core.firstOrNull()?.isLetterOrDigit() == true
        val afterSpace = core.lastOrNull()?.isLetterOrDigit() == true && suffix.firstOrNull()?.isLetterOrDigit() == true
        val insertion = (if (beforeSpace) " " else "") + core + (if (afterSpace) " " else "")
        val replacement = prefix + insertion + suffix
        val setText = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) return false

        val cursor = lower + insertion.length
        val setSelection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, setSelection)
        return true
    }

    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        val applicationWindows = runCatching { windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } }.getOrDefault(emptyList())
        val roots = applicationWindows.sortedByDescending { it.isFocused }.mapNotNull { runCatching { it.root }.getOrNull() }.toMutableList()
        runCatching { rootInActiveWindow }.getOrNull()?.let { root -> if (roots.none { it.windowId == root.windowId }) roots.add(0, root) }
        for (root in roots) {
            val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (focused?.isEditable == true) return focused
        }
        return null
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = runCatching { node.inputType }.getOrDefault(0)
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun renderError(message: String) {
        bubble?.contentDescription = "Parakeet Dictation. $message. Tocca per riprovare."
    }

    private fun renderResult(message: String) {
        bubble?.contentDescription = "Parakeet Dictation. $message."
    }

    private fun openSetup() {
        startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        setColor(color)
        shape = GradientDrawable.OVAL
    }

    private class FloatSamples(capacity: Int) {
        private val samples = FloatArray(capacity)
        var size: Int = 0
            private set

        fun append(buffer: ShortArray, count: Int) {
            val limit = minOf(count, samples.size - size)
            for (index in 0 until limit) samples[size + index] = buffer[index] / 32768.0f
            size += limit
        }

        fun toArray(): FloatArray = samples.copyOf(size)
    }

    private class MicGlyph(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 26, 18)
            style = Paint.Style.STROKE
            strokeWidth = 2.3f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = resources.displayMetrics.density
            val cx = width / 2f
            val path = Path().apply {
                addRoundRect(cx - 4.3f * scale, height * 0.08f, cx + 4.3f * scale, height * 0.62f, 5f * scale, 5f * scale, Path.Direction.CW)
                moveTo(cx - 9f * scale, height * 0.48f)
                lineTo(cx - 9f * scale, height * 0.56f)
                cubicTo(cx - 9f * scale, height * 0.88f, cx + 9f * scale, height * 0.88f, cx + 9f * scale, height * 0.56f)
                lineTo(cx + 9f * scale, height * 0.48f)
                moveTo(cx, height * 0.82f)
                lineTo(cx, height * 0.98f)
                moveTo(cx - 5f * scale, height * 0.98f)
                lineTo(cx + 5f * scale, height * 0.98f)
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val BUBBLE_DIAMETER_DP = 54
        private const val BUBBLE_PREFERENCES = "parakeet_bubble_position"
        private const val BUBBLE_X_FRACTION = "x_fraction"
        private const val BUBBLE_Y_FRACTION = "y_fraction"
        private const val MAX_RECORD_SECONDS = 120
        private const val MAX_SAMPLES = ModelFiles.SAMPLE_RATE * MAX_RECORD_SECONDS
        private const val MAX_EDITABLE_TEXT = 100_000
        private const val LIME = 0xffb8f36b.toInt()
    }
}
