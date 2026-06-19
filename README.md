# SpendAI

Open-source, local-first, on-device expense tracking for Android. SpendAI
intercepts financial SMS messages, extracts structured transactions through
a three-agent LLM pipeline (A1 parse → A2 resolve → A3 audit), persists
them in a Room database, and exposes them through a multi-screen Compose
UI with a hand-rolled agentic "Ask AI" chat on top.

**No data leaves the device by default.** The model can run against a
local on-device backend (LiteRT-LM) or against an external API the user
explicitly configures (Gemini, OpenAI-compatible, Anthropic, ZHIPU,
Custom). The user controls which, and which model, from the in-app
**Model settings** screen.

## Status

Production-ready for personal use:

* **Three-agent SMS pipeline** (A1 SMS parser → A2 entity resolver → A3
  auditor) running on every incoming message, with a manual-override
  reprompt path.
* **Merchant knowledge layer** — the user can mark counterparties as
  themself ("Deep G is me") or attach freeform notes / category hints
  ("MOHAN KUSHWANA is pani puri vendor") either through the Merchants
  management screen or conversationally via Ask AI. Both surfaces share
  the same allowlisted write path; both trigger A3 reprompts on
  affected transactions.
* **Insights screen** — fixed-schema KPIs, category donut, daily trend,
  top merchants, day-of-week breakdown, all driven by hand-rolled
  Compose charts.
* **Ask AI** — multi-turn chat with two tools (`query_database` for
  read-only analytics, `mutate_merchant` for the knowledge layer).
  Reasoning-model friendly: strips `<think>` blocks, tries every
  balanced JSON object in the response, retries on parse failure,
  and runs a model-as-judge verifier when the user opts in.
* **Edit / Review / Debug log** screens for the audit trail and manual
  correction flow.

## Architecture

See [AGENTS.md](AGENTS.md) for the full agent architecture. In short:

```
SMS  ->  A1 (parse)  ->  A2 (resolve + isSelf/metadata)  ->  A3 (audit + link)
                                                                       |
                                                                       v
                                                          Room: spend_transaction
                                                                       |
Ask AI chat ------------------------------------------------------ SQL queries
                                                                       |
Mutate-merchant tool  ->  MerchantMutator  ->  merchant / merchant_metadata
                                                       |
                                                       v
                                              + reprompt_job (durable A3 re-run)
```

## Project layout

```
spendai/
  app/
    src/main/
      AndroidManifest.xml
      assets/                                # placeholder for sideloaded model
      java/com/spendai/app/
        SpendAiApp.kt                        # Application + service locator
        data/local/                          # Room: entities, DAOs, db
        data/repository/                     # facades over the DAOs
        receiver/SmsReceiver.kt              # captures incoming SMS
        worker/DailyParsingWorker.kt         # background batch processor
        service/IngestionService.kt          # foreground ingestion + A3 reprompt
        domain/agent/                        # A1, A2, A3 + JSON contracts
        domain/agent/insights/               # Ask AI: agent, actions, tool results
        domain/ingestion/IngestionPipeline.kt
        inference/GemmaInferenceEngine.kt    # 5 backends, OkHttp, rate-limit backoff
        ui/                                  # Compose screens
          home/
          transactions/
          insights/                          # fixed-schema + Ask AI chat
          merchants/                         # merchant knowledge management
          sources/
          review/
          edit/
          debug/
          setup/
          permissions/
          download/
    src/test/                               # JVM unit tests (Robolectric + mockk + Turbine)
    src/androidTest/                        # instrumented tests
  app/schemas/                               # Room schema exports (per version)
  AGENTS.md                                 # agent + Ask-AI architecture
  README.md                                 # this file
```

## Build

The project uses the Gradle wrapper.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Requires Android SDK platform 35 and JDK 17+ (the host running this
project ships JDK 25, which AGP 8.7.3 supports).

## Run

Install the debug APK on a connected device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.spendai.app/.ui.MainActivity
```

The app opens on the Home screen. The Home screen has a header overflow
menu with the "knowledge" surfaces:

* **Sources & categories** — review the financial sources the pipeline
  has seen, label the ones the user wants confirmed.
* **Merchants** — toggle `isSelf`, add NOTE / CATEGORY_HINT / LABEL
  metadata per merchant. Edits flow through the same mutator Ask AI
  uses, with the same ripple (self-link + reprompts).
* **Debug log** — every ingestion run lands here with the A1 / A2 / A3
  prompt + response + outcome.
* **Model settings** — switch the inference backend, set the API key +
  base URL + model name, manage the on-device LiteRT-LM session.

The **Insights** screen has two surfaces:

* A fixed-schema view with KPIs, category donut, daily trend, top
  merchants, day-of-week breakdown. All queries exclude
  self-transfers and self-flagged merchants by default.
* An **Ask AI** chat (header pill) that streams the model's replies
  in real time, runs SQL queries, and edits merchant knowledge.

## Model setup

The on-device backend (LiteRT-LM) is **opt-in**. By default the app
talks to the user-configured external API. The user can stay entirely
offline by:

1. Sideloading the Gemma 4 E2B model to the app's private external
   dir:
   ```sh
   adb push gemma-4-E2B-it.litertlm \
     /sdcard/Android/data/com.spendai.app/files/models/
   ```
2. Opening **Model settings**, switching the backend to
   **On-device (LiteRT-LM)**, and tapping the "Probe" button to
   warm the engine.

If the user wants to use a hosted model (Gemini, OpenAI-compatible,
Anthropic, ZHIPU, Custom), the steps are: open **Model settings**,
pick the backend, paste the API key + (optionally) base URL + model
name, and tap "Probe" to confirm the engine can talk to it.

## Permissions

The manifest declares `RECEIVE_SMS`, `READ_SMS`, and
`POST_NOTIFICATIONS`. These are dangerous permissions on Android 6+
and must be granted at runtime before the SMS receiver will fire. The
onboarding flow walks the user through the consent screen before the
rest of the UI unlocks.

## Hardware acceleration (on-device backend only)

The local inference engine tries the most powerful backend first and
falls through on failure:

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

GNU General Public License v3.0. See [LICENSE](LICENSE) for the full text.
