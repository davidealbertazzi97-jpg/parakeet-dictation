# Parakeet model attribution

The model files downloaded by this app are a converted and quantized ONNX export of NVIDIA's Parakeet TDT 0.6B v3 speech-recognition model.

- **Original model:** [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
- **Original model license:** [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- **ONNX export:** [sherpa-onnx Parakeet TDT 0.6B v3 int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78)
- **Pinned export revision:** `2bda32ec70b097a55adaa07d9a7173915b43cc78`
- **Languages:** 25 European languages, including Italian.

The export changes the original model's file format to ONNX and quantizes its weights to int8 for use with sherpa-onnx. It is a format conversion and quantization, not a model retraining. The app downloads the encoder, decoder, joiner, and token file at the pinned revision; it does not include the model weights in the source repository or APK. Downloaded files are checked against the SHA-256 values in `ModelFiles.kt`.

The original model is created by NVIDIA and distributed under CC BY 4.0. The converted export is provided by the sherpa-onnx Hugging Face account. This notice identifies the source model and describes the conversion; it does not replace the model license or grant rights beyond it.
