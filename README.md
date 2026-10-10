# CYN.AI

**CYN** is a mobile-first, local-first AI assistant for Android, named after Cyn from *Murder Drones*.

The goal is a compact chat app that runs open-weight models on-device, prioritizes coding help, understands images, and grows through modular tools such as a user-approved GitHub workspace connector. The core app should not require a paid API.

## Current status

The initial Android foundation and GitHub Actions APK workflow are in place. This is a **UI/build milestone**, not a claim that local inference is already integrated.

### Design goals
- Android-first, small modular files, and GitHub Actions APK builds.
- Local GGUF model import and model switching.
- Coding model and vision model roles, loaded on demand rather than all at once.
- Image attachments from the start; video understanding only when the chosen runtime/model supports it.
- Local chat history and clear generation/progress feedback.
- Optional modular tools. GitHub write actions must require user confirmation.
- No bundled multi-gigabyte model files in the source repository.

## Models you already have

These are registered as candidates; runtime compatibility and device memory use still need testing:

| File | Intended role | Notes |
|---|---|---|
| `qwen2.5-coder-3b-instruct-q8_0.gguf` | Coding/chat | Q8 quantization is memory-heavy for a phone; check available memory before loading. |
| `SmolVLM2-500M-Video-Instruct-f16.gguf` | Lightweight vision/video candidate | Video support depends on the exact model packaging and runtime integration. F16 may use substantial RAM. |
| `SmolVLM2-2.2B-Instruct.i1-Q6_K.gguf` | Image/vision candidate | Larger and more memory-intensive; it may require compatible multimodal runtime/projector components. |

The app should let you import files directly from Android storage. You do **not** need to upload the model binaries to this repository.

## Large model files

Normal GitHub repository files are blocked above 100 MiB. Git LFS has per-file and free-quota limits, so it is not a good default distribution mechanism for these models. Keep model binaries outside the source repo; use Android's file picker for local import, and later add optional verified model download links.

## Build an APK

Open the **Actions** tab and select **Android APK**. Successful runs upload a debug APK artifact named `CYN-debug-apk`.

## Architecture direction

- `app/`: Android UI, chat state, attachment and model-picker flows.
- `runtime/` (future): local inference adapter behind a small interface.
- `models/` (future): model metadata and compatibility checks, not model binaries.
- `plugins/` (future): isolated optional tool integrations.

The first milestone is a clean buildable Android base. Local text inference and multimodal inference are separate milestones and will be reported as such.
