# Parakeet Dictation

App Android per dettatura locale con bolla fluttuante, usando la tastiera Android già installata. Non registra una tastiera propria e non richiede di cambiare l'IME.

## Avvio

1. Compila e installa con `./gradlew :app:assembleDebug`.
2. Apri Parakeet Dictation e scarica Parakeet TDT v3 (circa 671 MB). Il download è riprendibile e i file sono verificati con SHA-256.
3. Concedi il permesso microfono.
4. Apri le impostazioni di accessibilità dall'app, leggi la descrizione di Parakeet Dictation e attiva il servizio.
5. Apri un campo di testo in un'altra app: compare un cerchio verde trascinabile sopra la tastiera e il recognizer inizia subito a caricarsi.
6. Tocca la bolla per registrare; toccala di nuovo per fermare, trascrivere e inserire il testo.

Per creare una release firmata, configura la chiave localmente tramite il file ignorato `keystore.properties` nella root del progetto oppure con le variabili d'ambiente `PARAKEET_SIGNING_STORE_FILE`, `PARAKEET_SIGNING_STORE_PASSWORD`, `PARAKEET_SIGNING_KEY_ALIAS` e `PARAKEET_SIGNING_KEY_PASSWORD`, quindi esegui `./gradlew :app:assembleRelease`. La build si interrompe se la firma di release non è configurata. L'APK viene generato in `app/build/outputs/apk/release/app-release.apk`.

## Comportamento

- La bolla è un cerchio verde `TYPE_ACCESSIBILITY_OVERLAY`, senza pannello o bordo scuro. Puoi trascinarla in qualsiasi punto dello schermo; la posizione viene ricordata. Compare quando il servizio vede la finestra della tastiera Android e scompare quando la tastiera viene chiusa.
- La chiusura della tastiera avvia un timer di 60 secondi. Se si riapre entro quel tempo, il modello resta in memoria; al termine il recognizer viene rilasciato. I file del modello restano nella memoria privata dell'app.
- La dettatura non parte automaticamente: il microfono si attiva solo dopo il tocco sulla bolla. I campi password vengono esclusi. L'audio è mantenuto in RAM per la trascrizione locale e non viene inviato in rete.
- Per inserire il risultato, il servizio usa l'albero di accessibilità del campo attivo e le azioni `ACTION_SET_TEXT` / `ACTION_SET_SELECTION`. Alcuni campi personalizzati, app bancarie, browser o finestre di sistema possono non esporre un campo modificabile accessibile; in quel caso il testo non può essere inserito. È necessaria una prova sul dispositivo e sulle app usate quotidianamente.
- Parakeet TDT v3 è offline e non è streaming: la frase viene prodotta dopo Stop. La registrazione si interrompe dopo un massimo di due minuti.
- Il runtime preferisce l'Execution Provider Android NNAPI. Il driver può assegnare calcoli a CPU, GPU o NPU; questa app non può forzare o confermare che il driver scelga la GPU. Se NNAPI non parte o il decode accelerato fallisce, prova la CPU.
- Nella schermata iniziale puoi attivare **Mantieni il modello in RAM · CPU**. In questa modalità il modello viene caricato sulla CPU appena attivi l'opzione e resta residente anche quando la tastiera è chiusa, finché Android mantiene vivo il processo. Disattivandola, il modello viene scaricato un minuto dopo la chiusura della tastiera.
- Non servono API o Google Play Services per il riconoscimento locale. La connessione serve soltanto per scaricare il modello da Hugging Face.
- La build include solo `arm64-v8a`. Non c'è un telefono collegato in questo workspace: caricamento su GPU/NPU, accessibilità dei campi, acquisizione microfono e latenza devono essere verificati su un dispositivo reale.

## Permessi

L'app richiede microfono e servizio di accessibilità. Il servizio usa l'accessibilità per rilevare la finestra della tastiera, individuare il campo modificabile attivo ed inserirvi la trascrizione richiesta dall'utente. Non legge il contenuto di altri campi e non avvia l'ascolto finché la bolla non viene toccata. L'app non usa `SYSTEM_ALERT_WINDOW`, IME personalizzato o permesso di accesso alle notifiche.

## Componenti e licenze

- Il codice originale di Parakeet Dictation è distribuito con GNU AGPL v3 o successiva: testo in [LICENSE](LICENSE).
- Il modulo Android sherpa-onnx v1.13.8 e i relativi wrapper nativi mantengono Apache License 2.0, in [sherpa_onnx/LICENSE](sherpa_onnx/LICENSE).
- `libonnxruntime.so` usa ONNX Runtime 1.28.2, con licenza MIT e avvisi delle dipendenze in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e `licenses/`.
- Il modello Parakeet TDT 0.6B v3 int8 viene scaricato dall'app e non è incluso nel sorgente o nell'APK. La revisione fissata `2bda32ec70b097a55adaa07d9a7173915b43cc78` è attribuita sotto CC BY 4.0; dettagli in [LICENSE-PARAKEET.md](LICENSE-PARAKEET.md).

Le licenze dei componenti di terze parti restano distinte e non sono sostituite dalla licenza del codice originale. I testi e gli avvisi sono inclusi anche nell'APK sotto `assets/licenses/`.
