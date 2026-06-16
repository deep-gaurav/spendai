# SpendAI

Open-source, local-first, on-device expense tracking for Android. Intercepts
financial SMS messages, stores them securely, and runs a small LLM (Gemma 4
E2B) on-device via Google AI Edge LiteRT-LM to extract structured expense
records.

**No cloud. No analytics. No network at runtime.** Your financial data
never leaves the device.

## Status

**Phase 1 — data plumbing and inference engine.** No UI yet. The three
foundational pillars are in place:

1. **Room database** for raw SMS and known financial sources.
2. **SMS receiver + WorkManager** background pipeline.
3. **LiteRT-LM inference engine** for the Gemma 4 E2B model with an
   NPU → GPU → CPU fallback chain.

## Project layout

```
spendai/
  app/
    src/main/
      AndroidManifest.xml
      assets/
        models/                          # placeholder; see "Model setup" below
        README.md                        # sideload workflow
      java/com/spendai/app/
        SpendAiApp.kt                    # Application + service locator
        data/local/                      # Room (entities, DAOs, database)
        data/repository/                 # facades over the DAOs
        receiver/SmsReceiver.kt          # captures incoming SMS
        worker/DailyParsingWorker.kt     # background batch processor
        inference/                       # LiteRT-LM wrapper
    src/test/                            # JVM unit tests
    src/androidTest/                     # instrumented tests
```

## Build

The project uses the Gradle wrapper.

```sh
./gradlew :app:assembleDebug
```

Requires Android SDK platform 35 and JDK 17+ (the host running this
project ships JDK 25, which AGP 8.7.3 supports).

## Model setup

The Gemma 4 E2B model is **2.58 GB** and is not bundled with the APK.
Sideload it on a connected device:

```sh
# 1. Download from Hugging Face
#    https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
#    pick `gemma-4-E2B-it.litertlm`

# 2. Push to the app's private external dir
adb push gemma-4-E2B-it.litertlm \
  /sdcard/Android/data/com.spendai.app/files/models/
```

The `ModelInstaller` will detect the file on first launch. See
[app/src/main/assets/README.md](app/src/main/assets/README.md) for the
detailed workflow including the dev-only assets fallback.

## Permissions

The manifest declares `RECEIVE_SMS`, `READ_SMS`, and
`POST_NOTIFICATIONS`. These are "dangerous" permissions on Android 6+
and must be granted at runtime before the SMS receiver will fire. The
consent screen is a Phase 1.5 follow-up — for now the permissions are
declared but the UI does not request them.

## Hardware acceleration

The inference engine tries the most powerful backend first and falls
through on failure:

| Order | Backend | Where it lands                       | Perf (S26 Ultra) |
|------:|---------|--------------------------------------|------------------|
| 1     | NPU     | Qualcomm QNN (`libQnnHtp.so`)        | best, fragile    |
| 2     | GPU     | OpenCL / ML Drift                    | 3.8k tk/s prefill|
| 3     | CPU     | XNNPack                              | 557 tk/s prefill |

NPU on community Hugging Face models is famously fragile — the
LiteRT-LM Kotlin guide and the GDE deep-dive both document
`LiteRtLmJniException("TF_LITE_AUX not found")` as the standard
failure mode. The fallback chain is the production pattern.

Multi-Token Prediction (MTP) is on by default. It yields a 2.2x GPU
decode speedup with no quality loss per Google's own benchmark page.

## License

Apache 2.0.
