# Parakeet model attribution

The model downloaded by this app is a converted and int8-quantized ONNX export of NVIDIA's Parakeet TDT 0.6B v3 speech-recognition model.

- Original model: <https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3>
- Original model license: Creative Commons Attribution 4.0 International (CC BY 4.0), <https://creativecommons.org/licenses/by/4.0/>.
- Converted ONNX export: <https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78>
- Pinned export revision: `2bda32ec70b097a55adaa07d9a7173915b43cc78`.
- Languages: 25 European languages, including Italian.

The conversion changes the file format to ONNX and quantizes the weights to int8 for sherpa-onnx; it does not retrain the model. NVIDIA created the original model. The app downloads the encoder, decoder, joiner, and token file from the pinned export and verifies their SHA-256 digests. The model weights are not included in this APK.
