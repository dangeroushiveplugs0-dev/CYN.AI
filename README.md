# CYN.AI

**CYN** is a mobile-first, local-first AI assistant for Android, named after Cyn from *Murder Drones*.

The goal is a compact chat app that runs open-weight models on-device, prioritizes coding help, understands supported attachments, and grows through modular tools such as a user-approved GitHub workspace connector. The core app does not require a paid API.

## Current implementation

- Kotlin + Jetpack Compose Android chat UI.
- GGUF model registration through Android's document picker.
- Local file inspection for supported text, code, archives, PDF text, and file metadata.
- **llama.cpp Android JNI runtime integration in progress**: CI checks out a pinned upstream revision, builds its native CPU runtime for ARM64, and the chat adapter stages imported GGUFs into app-private storage before loading them.
- Token streaming from the inference engine into the assistant message.
- The build must pass and a real model must be tested on-device before this integration is considered validated. This repository cannot verify a model load or memory use on your phone.

## Models you already have

These are candidates, not a guarantee that every file will load or fit in available RAM:

| File | Intended role | Notes |
|---|---|---|
| `qwen2.5-coder-3b-instruct-q8_0.gguf` | Coding/chat | Q8 quantization is memory-heavy on a phone. A 3B Q8 model may fail to load or be killed by Android if available RAM is insufficient. |
| `SmolVLM2-500M-Video-Instruct-f16.gguf` | Vision/video candidate | Video support depends on the exact model packaging and compatible multimodal runtime integration. |
| `SmolVLM2-2.2B-Instruct.i1-Q6_K.gguf` | Vision candidate | Multimodal models may need additional projector/vision components and a compatible runtime path. |

Start by trying the smallest compatible text GGUF you have. Do not assume that successful file import means the model is compatible or memory-safe.

## Important runtime details

- The native build currently targets **ARM64 Android devices** to keep the APK and build scope smaller.
- On first load, CYN copies the selected GGUF into its private app storage because the native engine loads from a filesystem path. Keep enough free storage for a second copy of the model.
- Only one model is intended to be loaded at a time.
- Attachment metadata/text is passed to the text model as prompt context. This does **not** yet mean the model can interpret raw image pixels, audio, video, or complex 3D geometry.
- No model binaries are bundled in the repository.

## Build an APK

Open the **Actions** tab and select **Android APK**. A successful run uploads a debug APK artifact named `CYN-debug-apk`. Native llama.cpp compilation takes longer than the previous UI-only build.

## Architecture

- `app/`: Android UI, model library, attachment inspection, runtime adapter.
- `third_party/llama.cpp/`: checked out by CI at a pinned upstream revision; not stored as a large vendored copy in this repository.
- `plugins/`: future isolated tool integrations. GitHub write actions should require explicit user confirmation.
