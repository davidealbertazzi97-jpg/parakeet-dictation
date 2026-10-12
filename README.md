# Parakeet Dictation

**Local, offline voice dictation for Android.** A movable microphone bubble appears above the keyboard you already use; no custom keyboard or cloud transcription is involved.

This is an independent, open-source alternative for people who want a WhisperFlow-style dictation workflow with on-device recognition. It is not affiliated with, endorsed by, or sponsored by WhisperFlow, and it does not include WhisperFlow code, assets, or branding.

## Features

- Uses Android's existing keyboard. The green bubble appears while a keyboard is visible, can be moved, and can be hidden from the app's settings.
- Hold the bubble to record until release, or tap to start and tap again to stop. Recording shows an audio meter and a cancel control.
- Runs Parakeet TDT 0.6B v3 locally through sherpa-onnx. Audio is transcribed after recording stops; this model is not streaming.
- Starts loading the model when the keyboard appears. By default, it is released one minute after the keyboard closes. Optional settings keep it resident on the CPU or through Android NNAPI.
- Includes an in-app AGPL warranty notice, the complete AGPL text, and third-party license notices.
- Does not require Google Play Services. Internet access is used only when the user downloads the model.

## Requirements and limitations

- Android 8.0 (API 26) or newer, with a 64-bit ARM processor (`arm64-v8a`).
- Microphone permission and the Android accessibility service are required. The service detects the keyboard, checks the focused editable field, and inserts the dictated text there. Read the [privacy and data-flow notes](PRIVACY.md) before enabling it.
- Text insertion depends on the target app exposing its focused text field to Android accessibility. Custom editors and some protected fields may not support insertion. The bubble was checked with Gboard in Chrome on an emulator; Instagram and Facebook comment editors have not been verified on a physical device.
- NNAPI may delegate work to an available CPU, GPU, or NPU according to the Android driver. This runtime cannot select GPU and NPU separately or guarantee which device is used.
- The model is about 671 MB and is downloaded on demand from Hugging Face. Its weights are not included in the source repository or APK.

## Build and run

Install JDK 17 and Android SDK 35, then build the debug APK:

```sh
./gradlew :app:assembleDebug
```

Open the app, download the model, grant microphone access, and enable **Parakeet Dictation** under Android's Accessibility settings. Open a text field in another app to show the bubble.

For a signed release build, create a local, ignored `keystore.properties` file using the keys shown in `app/build.gradle`, or set the `PARAKEET_SIGNING_*` environment variables. Never commit a keystore, signing properties, or passwords. Then run:

```sh
./gradlew :app:assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Privacy

Speech recognition runs on the device. Recorded audio is held in app memory for transcription and is not sent to a server by this app. The accessibility service reads the focused editable field's current text in memory when inserting a transcript, so it can preserve surrounding text and the cursor position; password fields are excluded. The operating system still grants the service access to interactive windows while it is enabled. See [PRIVACY.md](PRIVACY.md) for the full data flow and how to disable the service.

## Licenses and attributions

- Original app code: [GNU AGPL v3 or later](LICENSE).
- sherpa-onnx wrappers and native components: Apache License 2.0; see [sherpa_onnx/LICENSE](sherpa_onnx/LICENSE).
- ONNX Runtime and bundled notices: MIT and additional third-party licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [`licenses/`](licenses/).
- Parakeet TDT model: NVIDIA's original model is CC BY 4.0; the app downloads a converted, int8 ONNX export. See [LICENSE-PARAKEET.md](LICENSE-PARAKEET.md).

These licenses apply to their respective components; the app's AGPL license does not replace third-party terms.

---

# Parakeet Dictation (Italiano)

**Dettatura vocale locale e offline per Android.** Una bolla trascinabile compare sopra la tastiera che usi già: non installa una tastiera personalizzata e non invia l'audio al cloud per la trascrizione.

Questo progetto open source indipendente è un'alternativa a WhisperFlow per chi vuole dettare usando un modello sul dispositivo. Non è affiliato, approvato o sponsorizzato da WhisperFlow e non include codice, risorse grafiche o marchi di WhisperFlow.

## Funzioni

- Usa la tastiera Android esistente. La bolla verde compare con la tastiera aperta, si può spostare e si può nascondere dalle impostazioni dell'app.
- Tieni premuta la bolla per registrare fino al rilascio, oppure toccala per avviare e fermare la registrazione. Durante l'ascolto mostra un misuratore audio e un comando di annullamento.
- Esegue Parakeet TDT 0.6B v3 in locale tramite sherpa-onnx. La trascrizione viene prodotta al termine della registrazione; il modello non trascrive in streaming.
- Avvia il caricamento del modello quando appare la tastiera. Di norma lo rilascia un minuto dopo la chiusura. Le impostazioni opzionali lo mantengono pronto su CPU oppure tramite Android NNAPI.
- Include nell'app l'avviso AGPL sulla garanzia, il testo integrale della licenza e gli avvisi dei componenti di terze parti.
- Non richiede Google Play Services. La rete serve solo quando l'utente scarica il modello.

## Requisiti e limiti

- Android 8.0 (API 26) o successivo, con processore ARM a 64 bit (`arm64-v8a`).
- Servono il permesso microfono e il servizio di accessibilità Android. Il servizio rileva la tastiera, controlla il campo modificabile attivo e inserisce lì il testo dettato. Leggi [come vengono trattati i dati](PRIVACY.md) prima di attivarlo.
- L'inserimento dipende dal fatto che l'app usata esponga il campo attivo all'accessibilità Android. Alcuni editor personalizzati o protetti potrebbero non consentirlo. La bolla è stata verificata con Gboard e Chrome su emulatore; i campi commento di Instagram e Facebook non sono stati verificati su un telefono fisico.
- NNAPI può delegare il lavoro a CPU, GPU o NPU secondo il driver Android. Il runtime incluso non può selezionare GPU e NPU separatamente né garantire quale dispositivo venga usato.
- Il modello occupa circa 671 MB e viene scaricato su richiesta da Hugging Face. I pesi non sono inclusi nel repository o nell'APK.

## Compilazione e avvio

Installa JDK 17 e Android SDK 35, poi compila l'APK debug:

```sh
./gradlew :app:assembleDebug
```

Apri l'app, scarica il modello, autorizza il microfono e attiva **Parakeet Dictation** nelle impostazioni di accessibilità di Android. Apri un campo di testo in un'altra app per mostrare la bolla.

Per una release firmata, crea localmente il file ignorato `keystore.properties` con le chiavi indicate in `app/build.gradle`, oppure imposta le variabili d'ambiente `PARAKEET_SIGNING_*`. Non aggiungere mai al repository chiavi, file di firma o password. Poi esegui:

```sh
./gradlew :app:assembleRelease
```

L'APK release viene scritto in `app/build/outputs/apk/release/app-release.apk`.

## Privacy

Il riconoscimento vocale avviene sul telefono. L'audio registrato resta nella memoria dell'app durante la trascrizione e questa app non lo invia a un server. Per inserire il risultato, il servizio legge in memoria il testo del campo modificabile attivo, così da preservare il testo circostante e la posizione del cursore; i campi password vengono esclusi. Quando è attivo, Android concede comunque al servizio accesso alle finestre interattive. Consulta [PRIVACY.md](PRIVACY.md) per il flusso completo e per disattivare il servizio.

## Licenze e attribuzioni

- Codice originale dell'app: [GNU AGPL v3 o successiva](LICENSE).
- Wrapper e componenti nativi sherpa-onnx: Apache License 2.0, in [sherpa_onnx/LICENSE](sherpa_onnx/LICENSE).
- ONNX Runtime e relativi avvisi: licenza MIT e altre licenze di terze parti, in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e [`licenses/`](licenses/).
- Modello Parakeet TDT: il modello originale NVIDIA è CC BY 4.0; l'app scarica un'esportazione ONNX convertita e quantizzata int8. Vedi [LICENSE-PARAKEET.md](LICENSE-PARAKEET.md).

Le licenze si applicano ai rispettivi componenti; la licenza AGPL dell'app non sostituisce i termini delle dipendenze.
