# Privacy and data flow

Parakeet Dictation is designed for on-device speech recognition. The Android app contains no account system, advertising SDK, analytics SDK, or app-operated transcription service.

## Microphone and audio

- The microphone is opened only after the user starts recording from the floating bubble.
- Audio samples are held in the app process's memory for local inference. The app does not upload audio or deliberately write recordings to storage.
- Transcription is performed on the device with the downloaded Parakeet ONNX model. Text is produced after recording stops.

## Accessibility service and text fields

Android requires an accessibility service for the app to notice when a keyboard appears above other apps and to insert dictated text into the focused field. Enabling the service grants Android-level access to interactive windows while the service is running.

In this source version, the service:

- checks application windows for the input-focused editable node;
- checks the focused field's input type to avoid password fields;
- reads the focused field's current value and selection in memory when inserting a transcript, so existing text and cursor position can be preserved;
- does not send window contents, field text, or transcripts to a Parakeet Dictation server.

The Android accessibility permission is broader than the app's intended use. The implementation is open source so this behavior can be reviewed. Hiding the bubble in the app does **not** revoke the accessibility permission; to stop the service, disable **Parakeet Dictation** in Android **Settings → Accessibility**.

## Model download and network

The app requests model files from the pinned Hugging Face repository only when the user starts the download. It verifies each file's size and SHA-256 digest before marking the model installed. The model is kept under the app's private, no-backup files directory. The app sends no recorded audio or transcript as part of this download. Hugging Face receives ordinary network request metadata (such as the device's IP address) under its own policies.

No Google Play Services, analytics, or crash-reporting endpoint is used by this project. Android, the keyboard, and the app into which a transcript is inserted have their own data and privacy behavior; Parakeet Dictation cannot control what those apps do with text you enter.

## Local state and deletion

The downloaded model and app settings are stored in app-private storage. Uninstalling the app removes that private data. The model can also be removed by clearing the app's storage in Android settings. The audio buffer is used for inference in memory and is not saved as a recording file.

## Review the implementation

Relevant code: `DictationAccessibilityService.kt`, `DictationEngine.kt`, `ModelDownloader.kt`, and `ModelFiles.kt` under `app/src/main/java/it/davide/parakeetdictation/`.
