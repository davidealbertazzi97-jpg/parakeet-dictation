# Avvisi sui componenti di terze parti

Il codice originale di Parakeet Dictation è concesso in licenza secondo GNU AGPL v3 o successiva. Le licenze dei componenti elencati qui sotto restano separate.

## sherpa-onnx Android 1.13.8

Il modulo `sherpa_onnx/` contiene wrapper Kotlin e librerie native di sherpa-onnx. È distribuito secondo Apache License 2.0; il testo è in [sherpa_onnx/LICENSE](sherpa_onnx/LICENSE). Sorgente upstream: <https://github.com/k2-fsa/sherpa-onnx>.

## ONNX Runtime 1.28.2

La libreria nativa `sherpa_onnx/src/main/jniLibs/arm64-v8a/libonnxruntime.so` è distribuita secondo la licenza MIT. Il testo MIT e gli avvisi delle dipendenze di ONNX Runtime sono inclusi in `app/src/main/assets/licenses/`. Sorgente upstream: <https://github.com/microsoft/onnxruntime>.

Negli avvisi upstream inclusi sono stati rimossi gli indirizzi e-mail di contatto; i nomi, le attribuzioni di copyright e i testi delle licenze sono mantenuti.

## Modello Parakeet

L'app scarica Parakeet TDT 0.6B v3 int8 alla prima richiesta dell'utente. Il modello non è incluso in questo repository o nell'APK. L'attribuzione della revisione fissata e la licenza CC BY 4.0 sono in [LICENSE-PARAKEET.md](LICENSE-PARAKEET.md).
