# Third-party components

Parakeet Dictation's original app code is licensed under the GNU AGPL version 3 or later. The licenses for third-party components remain separate.

## sherpa-onnx Android 1.13.8

The `sherpa_onnx/` module contains sherpa-onnx Kotlin wrappers and native libraries under the Apache License 2.0. The license text is available in this app as `licenses/Apache-2.0.txt`. Upstream: <https://github.com/k2-fsa/sherpa-onnx>.

## ONNX Runtime 1.28.2

The app bundles `libonnxruntime.so` under the MIT License. The license and upstream dependency notices are available in this app as `licenses/ONNXRUNTIME-MIT-LICENSE.txt` and `licenses/ONNXRUNTIME-THIRD-PARTY-NOTICES.txt`. Upstream: <https://github.com/microsoft/onnxruntime>.

The bundled upstream notices omit contributor contact email addresses for privacy. Names, copyright attributions, and license texts are retained.

## Parakeet TDT model

The app downloads a converted int8 ONNX export of NVIDIA's Parakeet TDT 0.6B v3 model on request; model weights are not included in the APK. NVIDIA's original model is licensed under CC BY 4.0. This app uses the export at revision `2bda32ec70b097a55adaa07d9a7173915b43cc78`. Attribution and conversion details are available as `licenses/Parakeet-Model-CC-BY-4.0.md`.
