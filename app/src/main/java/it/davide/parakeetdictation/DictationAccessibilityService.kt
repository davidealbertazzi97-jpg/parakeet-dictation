package it.davide.parakeetdictation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
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
import android.os.SystemClock
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
    private var recordingVisualizer: RecordingVisualizerView? = null
    private var visualizerParams: WindowManager.LayoutParams? = null
    private var cancelButton: View? = null
    private var cancelParams: WindowManager.LayoutParams? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var keyboardVisible = false
    private var keyboardTop = 0
    private var bubbleHidden = false
    private var engineKeyboardActive = false
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
    private var recordingStartedByPress = false
    private var recordingBeforePress = false
    private var handleTapOnRelease = false
    private var suppressClickAction = false
    private var pressStartedAt = 0L
    private var keyboardCheckAt = 0L

    private val keyboardCheck = Runnable {
        keyboardCheckAt = 0L
        updateKeyboardVisibility()
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BUBBLE_HIDDEN_KEY) {
            mainHandler.post { applyBubbleVisibilityPreference() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val preferences = getSharedPreferences(SETTINGS_PREFERENCES, MODE_PRIVATE)
        bubbleHidden = preferences.getBoolean(BUBBLE_HIDDEN_KEY, false)
        preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        DictationEngine.addObserver(this)
        DictationEngine.initialize(this)
        scheduleKeyboardCheck(INITIAL_KEYBOARD_CHECK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            sensitiveEditor = runCatching { event.source?.let(::isPasswordNode) ?: false }.getOrDefault(false)
        }
        val delay = if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) WINDOW_CHANGE_CHECK_MS else ACCESSIBILITY_EVENT_CHECK_MS
        scheduleKeyboardCheck(delay)
    }

    override fun onInterrupt() {
        if (keyboardVisible) setKeyboardVisible(false, keyboardTop)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(keyboardCheck)
        keyboardCheckAt = 0L
        stopRecording(submit = false)
        hideBubble()
        getSharedPreferences(SETTINGS_PREFERENCES, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(settingsListener)
        DictationEngine.removeObserver(this)
        DictationEngine.onKeyboardHidden()
        engineKeyboardActive = false
        super.onDestroy()
    }

    override fun onStatus(status: DictationEngine.Status) {
        if (!keyboardVisible) return
        renderStatus(status)
    }

    private fun scheduleKeyboardCheck(delayMs: Long) {
        val dueAt = SystemClock.uptimeMillis() + delayMs
        if (keyboardCheckAt != 0L && keyboardCheckAt <= dueAt) return
        mainHandler.removeCallbacks(keyboardCheck)
        keyboardCheckAt = dueAt
        mainHandler.postAtTime(keyboardCheck, dueAt)
    }

    private fun updateKeyboardVisibility() {
        val bounds = runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .map { Rect().also(it::getBoundsInScreen) }
                .maxByOrNull { it.width().toLong() * it.height().toLong() }
        }.getOrNull()
        val visible = bounds != null && bounds.height() >= dp(MIN_KEYBOARD_HEIGHT_DP) &&
            bounds.width() >= dp(MIN_KEYBOARD_WIDTH_DP)
        if (visible) {
            val editor = runCatching { focusedEditableNode() }.getOrNull()
            editorAvailable = editor?.isEditable == true
            sensitiveEditor = editor?.let(::isPasswordNode) == true
            setKeyboardVisible(true, bounds.top)
        } else if (keyboardVisible) {
            setKeyboardVisible(false, keyboardTop)
        }
        scheduleKeyboardCheck(if (keyboardVisible) VISIBLE_KEYBOARD_POLL_MS else HIDDEN_KEYBOARD_POLL_MS)
    }

    private fun setKeyboardVisible(visible: Boolean, top: Int) {
        val changed = keyboardVisible != visible
        keyboardVisible = visible
        keyboardTop = top
        if (visible && !bubbleHidden) {
            if (bubble == null) showBubble()
            else updateBubblePosition()
            refreshSensitiveState()
            if (changed) {
                mainHandler.postDelayed({ if (keyboardVisible) refreshSensitiveState() }, 250)
                mainHandler.postDelayed({ if (keyboardVisible) refreshSensitiveState() }, 700)
            }
        } else {
            hideBubble()
            if (recording) stopRecording(submit = false)
        }
        updateEngineKeyboardState()
    }

    private fun applyBubbleVisibilityPreference() {
        val hidden = isBubbleHidden(this)
        if (bubbleHidden == hidden) return
        bubbleHidden = hidden
        if (keyboardVisible && !bubbleHidden) {
            if (bubble == null) showBubble() else updateBubblePosition()
            refreshSensitiveState()
        } else {
            if (recording) stopRecording(submit = false)
            hideBubble()
        }
        updateEngineKeyboardState()
    }

    private fun updateEngineKeyboardState() {
        val shouldLoadForKeyboard = keyboardVisible && !bubbleHidden
        if (engineKeyboardActive == shouldLoadForKeyboard) return
        engineKeyboardActive = shouldLoadForKeyboard
        if (shouldLoadForKeyboard) DictationEngine.onKeyboardShown(this)
        else DictationEngine.onKeyboardHidden()
    }

    private fun showBubble() {
        val mic = FrameLayout(this).apply {
            background = circle(LIME)
            isClickable = false
            isFocusable = false
            addView(MicGlyph(this@DictationAccessibilityService), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        micButton = mic
        val overlay = FrameLayout(this).apply {
            isClickable = true
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            addView(mic, FrameLayout.LayoutParams(dp(BUBBLE_DIAMETER_DP), dp(BUBBLE_DIAMETER_DP), Gravity.CENTER))
            setOnClickListener { if (!suppressClickAction) onMicTapped() }
            setOnTouchListener(::handleBubbleTouch)
        }

        val point = screenSize()
        val prefs = getSharedPreferences(BUBBLE_PREFERENCES, MODE_PRIVATE)
        val overlaySize = dp(OVERLAY_SIZE_DP)
        val maxX = maxOf(0, point.x - overlaySize)
        val maxY = maxOf(0, point.y - overlaySize)
        val hasSavedPosition = prefs.contains(BUBBLE_X_FRACTION) && prefs.contains(BUBBLE_Y_FRACTION)
        val params = WindowManager.LayoutParams(
            overlaySize,
            overlaySize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (hasSavedPosition) (prefs.getFloat(BUBBLE_X_FRACTION, 1f) * maxX).toInt() else maxX
            y = if (hasSavedPosition) (prefs.getFloat(BUBBLE_Y_FRACTION, 0.5f) * maxY).toInt()
            else keyboardTop - dp((OVERLAY_SIZE_DP - BUBBLE_DIAMETER_DP) / 2) + dp(10)
            title = "Parakeet Dictation"
        }
        clampBubblePosition(params, point.x, point.y, overlaySize)
        try {
            windowManager.addView(overlay, params)
            bubble = overlay
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
        updateRecordingOverlayPositions()
    }

    private fun hideBubble() {
        hideRecordingOverlays()
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
                pressStartedAt = event.eventTime
                bubbleDragging = false
                recordingBeforePress = recording
                recordingStartedByPress = false
                handleTapOnRelease = false
                if (!recordingBeforePress) {
                    when (startRecordingOnPress()) {
                        PressStartResult.STARTED -> recordingStartedByPress = true
                        PressStartResult.NEEDS_SETUP -> handleTapOnRelease = true
                        PressStartResult.REJECTED -> Unit
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragStartX
                val deltaY = event.rawY - dragStartY
                val dragThreshold = maxOf(ViewConfiguration.get(this).scaledTouchSlop, dp(24))
                if (!bubbleDragging && (kotlin.math.abs(deltaX) > dragThreshold ||
                        kotlin.math.abs(deltaY) > dragThreshold)
                ) bubbleDragging = true
                if (bubbleDragging) {
                    if (recording) stopRecording(submit = false)
                    moveBubbleTo(dragWindowStartX + deltaX.toInt(), dragWindowStartY + deltaY.toInt())
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (bubbleDragging) {
                    saveBubblePosition()
                } else {
                    when {
                        recordingStartedByPress && recording -> {
                            // A short tap starts toggle recording; holding the button records only until release.
                            if (event.eventTime - pressStartedAt >= TAP_TO_TOGGLE_MS) stopRecording(submit = true)
                        }
                        recordingBeforePress && recording -> stopRecording(submit = true)
                        handleTapOnRelease -> onMicTapped()
                    }
                    suppressClickAction = true
                    try { view.performClick() } finally { suppressClickAction = false }
                }
                bubbleDragging = false
                recordingStartedByPress = false
                recordingBeforePress = false
                handleTapOnRelease = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (recordingStartedByPress && recording) stopRecording(submit = false)
                bubbleDragging = false
                recordingStartedByPress = false
                recordingBeforePress = false
                handleTapOnRelease = false
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
        updateRecordingOverlayPositions()
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
            button.background = circle(LIME)
        }
        updateRecordingOverlays(isRecording)
        val actionDescription = when {
            sensitiveEditor -> "Dettatura disattivata nel campo protetto"
            !editorAvailable -> "Campo di testo non disponibile. $phaseTitle"
            isBusy && !isRecording -> phaseTitle
            isRecording -> "Rilascia per trascrivere"
            else -> "Tieni premuto per dettare, oppure tocca per avviare"
        }
        // Keep status available to accessibility users without adding text around the green circle.
        bubble?.contentDescription = "Parakeet Dictation. ${status.message}. $actionDescription"
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

    private fun startRecordingOnPress(): PressStartResult {
        if (!keyboardVisible) return PressStartResult.REJECTED
        if (sensitiveEditor || focusedEditableNode()?.let(::isPasswordNode) == true) {
            sensitiveEditor = true
            renderStatus(DictationEngine.Status(DictationEngine.Phase.ERROR, "Campo protetto"))
            return PressStartResult.REJECTED
        }
        if (focusedEditableNode()?.isEditable != true) {
            renderError("Campo di testo non accessibile")
            return PressStartResult.REJECTED
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return PressStartResult.NEEDS_SETUP
        }
        return when (DictationEngine.currentPhase()) {
            DictationEngine.Phase.READY -> {
                startRecording()
                if (recording) PressStartResult.STARTED else PressStartResult.REJECTED
            }
            DictationEngine.Phase.MISSING_MODEL, DictationEngine.Phase.ERROR, DictationEngine.Phase.IDLE ->
                PressStartResult.NEEDS_SETUP
            else -> PressStartResult.REJECTED
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
        var lastLevelUpdate = 0L
        try {
            recorder.startRecording()
            while (recording && samples.size < MAX_SAMPLES) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    samples.append(buffer, count)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastLevelUpdate >= AUDIO_LEVEL_UPDATE_MS) {
                        lastLevelUpdate = now
                        var energy = 0.0
                        for (index in 0 until count) {
                            val sample = buffer[index] / 32768.0
                            energy += sample * sample
                        }
                        val rms = kotlin.math.sqrt(energy / count).toFloat()
                        val level = ((rms - AUDIO_LEVEL_NOISE_FLOOR) / AUDIO_LEVEL_FULL_SCALE).coerceIn(0f, 1f)
                        mainHandler.post {
                            if (recording) recordingVisualizer?.setAudioLevel(level)
                        }
                    }
                }
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
        updateRecordingOverlays(false)
        try { audioRecord?.stop() } catch (_: IllegalStateException) { }
        if (submit) DictationEngine.onRecordingStopped() else DictationEngine.cancelRecordingStatus()
    }

    private fun updateRecordingOverlays(active: Boolean) {
        if (!active || !keyboardVisible || bubble == null) {
            hideRecordingOverlays()
            return
        }
        showRecordingVisualizer()
        showCancelButton()
        updateRecordingOverlayPositions()
    }

    private fun showRecordingVisualizer() {
        if (recordingVisualizer != null) return
        val view = RecordingVisualizerView(this).apply { setRecording(true) }
        val size = screenSize()
        val params = WindowManager.LayoutParams(
            size.x,
            dp(VISUALIZER_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            title = "Parakeet Dictation audio level"
        }
        try {
            windowManager.addView(view, params)
            recordingVisualizer = view
            visualizerParams = params
        } catch (_: WindowManager.BadTokenException) {
            recordingVisualizer = null
            visualizerParams = null
        } catch (_: SecurityException) {
            recordingVisualizer = null
            visualizerParams = null
        }
    }

    private fun showCancelButton() {
        if (cancelButton != null) return
        val button = FrameLayout(this).apply {
            background = circle(CANCEL_RED)
            isClickable = true
            isFocusable = false
            contentDescription = "Annulla dettatura"
            addView(CancelGlyph(this@DictationAccessibilityService), FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
            setOnClickListener { if (recording) stopRecording(submit = false) }
        }
        val size = dp(CANCEL_BUTTON_DP)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "Annulla dettatura"
        }
        try {
            windowManager.addView(button, params)
            cancelButton = button
            cancelParams = params
        } catch (_: WindowManager.BadTokenException) {
            cancelButton = null
            cancelParams = null
        } catch (_: SecurityException) {
            cancelButton = null
            cancelParams = null
        }
    }

    private fun updateRecordingOverlayPositions() {
        val bubbleParams = windowParams ?: return
        val point = screenSize()
        val visualizer = recordingVisualizer
        val visualParams = visualizerParams
        if (visualizer != null && visualParams != null) {
            val visualHeight = dp(VISUALIZER_HEIGHT_DP)
            val visualY = bubbleParams.y + dp(OVERLAY_SIZE_DP) / 2 - visualHeight / 2
            visualParams.width = point.x
            visualParams.y = visualY.coerceIn(0, maxOf(0, point.y - visualHeight))
            runCatching { windowManager.updateViewLayout(visualizer, visualParams) }
            val centerY = bubbleParams.y + dp(OVERLAY_SIZE_DP) / 2 - visualParams.y
            val centerX = bubbleParams.x + dp(OVERLAY_SIZE_DP) / 2
            visualizer.setAnchor(centerX.toFloat(), centerY.toFloat(), point.x - centerX >= centerX)
        }

        val button = cancelButton
        val buttonParams = cancelParams
        if (button != null && buttonParams != null) {
            val buttonSize = dp(CANCEL_BUTTON_DP)
            val x = bubbleParams.x + dp(CANCEL_X_OFFSET_DP)
            val y = bubbleParams.y + dp(CANCEL_Y_OFFSET_DP)
            buttonParams.x = x.coerceIn(0, maxOf(0, point.x - buttonSize))
            buttonParams.y = y.coerceIn(0, maxOf(0, point.y - buttonSize))
            runCatching { windowManager.updateViewLayout(button, buttonParams) }
        }
    }

    private fun hideRecordingOverlays() {
        recordingVisualizer?.let { view ->
            view.setRecording(false)
            runCatching { windowManager.removeView(view) }
        }
        recordingVisualizer = null
        visualizerParams = null
        cancelButton?.let { view -> runCatching { windowManager.removeView(view) } }
        cancelButton = null
        cancelParams = null
    }

    private fun insertIntoFocusedField(text: String): Boolean {
        val node = runCatching { focusedEditableNode() }.getOrNull() ?: return false
        if (!node.isEditable || isPasswordNode(node)) return false

        val nodeText = node.text?.toString().orEmpty()
        val hintText = node.hintText?.toString().orEmpty()
        val showingHint = runCatching { node.isShowingHintText }.getOrDefault(false)
        val isHint = hintText.isNotBlank() && normalizeHint(nodeText) == normalizeHint(hintText)
        val isEmptyMessagePlaceholder = normalizeHint(nodeText).equals("messaggio", ignoreCase = true) &&
            node.textSelectionStart <= 0 && node.textSelectionEnd <= 0
        val existing = if (showingHint || isHint || isEmptyMessagePlaceholder) "" else nodeText
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

    private fun normalizeHint(value: String): String = value.trim().trimEnd('…', '.', ':').trim()

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

    private class RecordingVisualizerView(context: android.content.Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIME
            style = Paint.Style.STROKE
            strokeWidth = 3.4f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIME
            style = Paint.Style.STROKE
            strokeWidth = 8f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIME
            style = Paint.Style.FILL
        }
        private val barGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIME
            style = Paint.Style.FILL
        }
        private var recording = false
        private var anchorX = 0f
        private var anchorY = 0f
        private var meterToRight = true
        private var targetLevel = 0f
        private var displayedLevel = 0f

        fun setRecording(active: Boolean) {
            if (recording == active) return
            recording = active
            if (!active) {
                targetLevel = 0f
                displayedLevel = 0f
            }
            invalidate()
        }

        fun setAudioLevel(level: Float) {
            targetLevel = level.coerceIn(0f, 1f)
            invalidate()
        }

        fun setAnchor(x: Float, y: Float, barOnRight: Boolean) {
            anchorX = x
            anchorY = y
            meterToRight = barOnRight
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!recording) return

            val cycle = (SystemClock.uptimeMillis() % WAVE_CYCLE_MS).toFloat() / WAVE_CYCLE_MS
            val outerRadius = minOf(dp(WAVE_OUTER_RADIUS_DP).toFloat(), height / 2f - dp(WAVE_EDGE_MARGIN_DP))
            val innerRadius = dp(WAVE_INNER_RADIUS_DP).toFloat()
            for (index in 0 until WAVE_COUNT) {
                val phase = (cycle + index.toFloat() / WAVE_COUNT) % 1f
                val radius = innerRadius + (outerRadius - innerRadius) * phase
                val alpha = ((1f - phase) * 180f + 70f).toInt().coerceIn(60, 250)
                glowPaint.alpha = (alpha * 0.24f).toInt()
                wavePaint.alpha = alpha
                canvas.drawCircle(anchorX, anchorY, radius, glowPaint)
                canvas.drawCircle(anchorX, anchorY, radius, wavePaint)
            }

            displayedLevel += (targetLevel - displayedLevel) * if (targetLevel > displayedLevel) 0.42f else 0.18f
            val direction = if (meterToRight) 1f else -1f
            val meterCenter = anchorX + direction * dp(WAVE_OUTER_RADIUS_DP + METER_GAP_DP)
            val barWidth = dp(METER_BAR_WIDTH_DP).toFloat()
            val spacing = dp(METER_BAR_SPACING_DP).toFloat()
            val weights = floatArrayOf(0.42f, 0.66f, 0.84f, 1f, 0.9f, 0.72f, 0.52f, 0.76f, 0.46f)
            val time = SystemClock.uptimeMillis() / 1000f
            for (index in weights.indices) {
                val voiceShape = 0.72f + 0.28f * kotlin.math.abs(kotlin.math.sin(time * (3.4f + index * 0.17f) + index))
                val amplitude = displayedLevel * weights[index] * voiceShape
                val barHeight = dp(METER_MIN_HEIGHT_DP) + dp(METER_MAX_HEIGHT_DP) * amplitude
                val x = meterCenter + direction * (index - (weights.size - 1) / 2f) * spacing
                val top = anchorY - barHeight / 2f
                val rect = android.graphics.RectF(x - barWidth / 2f, top, x + barWidth / 2f, top + barHeight)
                barGlowPaint.alpha = (55 + amplitude * 80f).toInt().coerceIn(55, 135)
                barPaint.alpha = (185 + amplitude * 70f).toInt().coerceIn(185, 255)
                canvas.drawRoundRect(rect, barWidth, barWidth, barGlowPaint)
                canvas.drawRoundRect(rect, barWidth, barWidth, barPaint)
            }
            postInvalidateOnAnimation()
        }

        private fun dp(value: Int): Int = (value * density).toInt()
    }

    private class CancelGlyph(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.7f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = 3.5f * resources.displayMetrics.density
            canvas.drawLine(inset, inset, width - inset, height - inset, paint)
            canvas.drawLine(width - inset, inset, inset, height - inset, paint)
        }
    }

    companion object {
        private const val BUBBLE_DIAMETER_DP = 54
        private const val OVERLAY_SIZE_DP = 84
        private const val VISUALIZER_HEIGHT_DP = 156
        private const val CANCEL_BUTTON_DP = 38
        private const val CANCEL_X_OFFSET_DP = 54
        private const val CANCEL_Y_OFFSET_DP = -5
        private const val BUBBLE_PREFERENCES = "parakeet_bubble_position"
        private const val SETTINGS_PREFERENCES = "parakeet_dictation_settings"
        private const val BUBBLE_HIDDEN_KEY = "bubble_hidden"
        private const val BUBBLE_X_FRACTION = "x_fraction"
        private const val BUBBLE_Y_FRACTION = "y_fraction"
        private const val INITIAL_KEYBOARD_CHECK_MS = 100L
        private const val WINDOW_CHANGE_CHECK_MS = 50L
        private const val ACCESSIBILITY_EVENT_CHECK_MS = 90L
        private const val VISIBLE_KEYBOARD_POLL_MS = 250L
        private const val HIDDEN_KEYBOARD_POLL_MS = 500L
        private const val MIN_KEYBOARD_HEIGHT_DP = 90
        private const val MIN_KEYBOARD_WIDTH_DP = 120
        private const val TAP_TO_TOGGLE_MS = 200L
        private const val WAVE_CYCLE_MS = 1_300L
        private const val WAVE_COUNT = 4
        private const val WAVE_INNER_RADIUS_DP = 34
        private const val WAVE_OUTER_RADIUS_DP = 70
        private const val WAVE_EDGE_MARGIN_DP = 5
        private const val METER_GAP_DP = 34
        private const val METER_BAR_WIDTH_DP = 4
        private const val METER_BAR_SPACING_DP = 7
        private const val METER_MIN_HEIGHT_DP = 5
        private const val METER_MAX_HEIGHT_DP = 48
        private const val AUDIO_LEVEL_UPDATE_MS = 45L
        private const val AUDIO_LEVEL_NOISE_FLOOR = 0.006f
        private const val AUDIO_LEVEL_FULL_SCALE = 0.075f
        private const val MAX_RECORD_SECONDS = 120
        private const val MAX_SAMPLES = ModelFiles.SAMPLE_RATE * MAX_RECORD_SECONDS
        private const val MAX_EDITABLE_TEXT = 100_000
        private const val LIME = 0xffb8f36b.toInt()
        private const val CANCEL_RED = 0xffe5484d.toInt()

        fun isBubbleHidden(context: android.content.Context): Boolean = context
            .getSharedPreferences(SETTINGS_PREFERENCES, android.content.Context.MODE_PRIVATE)
            .getBoolean(BUBBLE_HIDDEN_KEY, false)

        fun setBubbleHidden(context: android.content.Context, hidden: Boolean) {
            context.getSharedPreferences(SETTINGS_PREFERENCES, android.content.Context.MODE_PRIVATE).edit()
                .putBoolean(BUBBLE_HIDDEN_KEY, hidden)
                .apply()
        }
    }

    private enum class PressStartResult { STARTED, NEEDS_SETUP, REJECTED }
}
