# Third-party software and models

Assist Plus is Apache-2.0 licensed. It builds on the work below; thank you to all of these projects.

| What | Used for | License |
|---|---|---|
| [Home Assistant Companion for Android](https://github.com/home-assistant/android) | Look of the Assist sheet (layout, colors, spacing, icon) | Apache-2.0 |
| [Material Design Icons](https://pictogrammers.com/library/mdi/) | Icons | Apache-2.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | On-device speech engine | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (inside sherpa-onnx) | Running the models | MIT |
| [NVIDIA Parakeet TDT-CTC 110M](https://huggingface.co/nvidia/parakeet-tdt_ctc-110m) | Speech to text | CC-BY-4.0 |
| [NVIDIA TitaNet small](https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small) | Voice ID | CC-BY-4.0 |
| [Silero VAD](https://github.com/snakers4/silero-vad) | Speech detection | MIT |
| [DPDFNet / DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) | Noise removal (Strong) | MIT / Apache-2.0 |
| [GTCRN](https://github.com/Xiaobin-Rong/gtcrn) | Noise removal (Light) | MIT |
| [icefall KWS zipformer (GigaSpeech)](https://github.com/k2-fsa/icefall) | Wake words | Apache-2.0 |
| [VoxLock](https://github.com/arbazsiddiqui/voxlock) (optional, not in releases) | Voice isolation | See project |
| [OkHttp](https://square.github.io/okhttp/), [AndroidX / Jetpack Compose](https://developer.android.com/jetpack), [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) | App plumbing | Apache-2.0 |
| [Google ML Kit / code scanner](https://developers.google.com/ml-kit/vision/barcode-scanning) | Barcode scanning (via Google Play services) | Google APIs Terms |
| [Open Food Facts](https://world.openfoodfacts.org/) | Product lookup by barcode (online) | ODbL (data) |

Models are downloaded from the sherpa-onnx releases by `scripts/download-models.sh` and are not stored in this repository.

"Home Assistant" is a trademark of the Open Home Foundation. Assist Plus is an independent project and is not affiliated with or endorsed by Home Assistant or Nabu Casa.
