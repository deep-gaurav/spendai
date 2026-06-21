# SpendAI

Open-source, private expense tracking for Android. SpendAI intercepts
financial SMS messages, extracts structured transactions through a
three-agent LLM pipeline (A1 parse → A2 resolve → A3 audit), persists
them in a Room database, and exposes them through a multi-screen Compose
UI with a hand-rolled agentic "Ask AI" chat on top.

The model runs against an LLM API the user explicitly configures from the
in-app **Model settings** screen — Gemini (default), OpenAI-compatible,
Anthropic, ZHIPU, a self-hosted Ollama instance, or a Custom endpoint. The
user supplies the API key + base URL + model name; nothing is hard-coded.
An on-device LiteRT-LM backend is still present in the codebase for
potential future re-enabling, but it is **not exposed in onboarding** and
is not the default.

## Status

Production-ready for personal use:

* **Three-agent SMS pipeline** (A1 SMS parser → A2 entity resolver → A3
  auditor) running on every incoming message, with a manual-override
  reprompt path.
* **Merchant knowledge layer** — the user can mark counterparties as
  themself ("Own Account is me") or attach freeform notes / category hints
  ("VENDOR XYZ is pani puri vendor") either through the Merchants
  management screen or conversationally via Ask AI. Both surfaces share
  the same allowlisted write path; both trigger A3 reprompts on
  affected transactions.
* **Insights screen** — fixed-schema KPIs, category donut, daily trend,
  top merchants, day-of-week breakdown, all driven by hand-rolled
  Compose charts.
* **Ask AI** — multi-turn chat with two tools (`query_database` for
  read-only analytics, `mutate_merchant` for the knowledge layer).
  Reasoning-model friendly: strips thinking blocks, tries every
  balanced JSON object in the response, retries on parse failure,
  and runs a model-as-judge verifier when the user opts in.
* **Edit / Review / Debug log** screens for the audit trail and manual
  correction flow.

> Note: the original design goal was fully on-device inference. It did not
> perform well enough, so the app now ships against user-configured LLM
> APIs. The on-device inference code has been kept in-tree so it can be
> re-enabled later; user-facing strings and onboarding no longer advertise
> it.

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
        inference/GemmaInferenceEngine.kt    # API backends + retained on-device backend
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
          download/                          # onboarding: model/API configuration
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
  base URL + model name.

The **Insights** screen has two surfaces:

* A fixed-schema view with KPIs, category donut, daily trend, top
  merchants, day-of-week breakdown. All queries exclude
  self-transfers and self-flagged merchants by default.
* An **Ask AI** chat (header pill) that streams the model's replies
  in real time, runs SQL queries, and edits merchant knowledge.

## Model setup

By default the app talks to a user-configured LLM API. To use a hosted
model (Gemini, OpenAI-compatible, Anthropic, ZHIPU, Custom) or a
self-hosted Ollama instance: open **Model settings** (or step through
onboarding, which lands on the **Model Configuration** screen), pick the
backend, paste the API key + (optionally) base URL + model name, and tap
**Probe** to confirm the engine can reach it. We recommend Google Gemini
in AI Studio as a free option (using `gemma-4-31b-it`). SpendAI requires
a model with at least a 64K context window to resolve daily ledger
groupings.

The on-device LiteRT-LM backend is retained in
[inference/GemmaInferenceEngine.kt](app/src/main/java/com/spendai/app/inference/GemmaInferenceEngine.kt)
and [inference/BackendStrategy.kt](app/src/main/java/com/spendai/app/inference/BackendStrategy.kt)
but is **not surfaced in onboarding or the Model settings UI**. Re-enabling
it is a code change (wire the backend selector back into the settings
screen and point the engine at a sideloaded model); the inference path and
hardware-acceleration fallback chain below remain in place for that
future use.

## Permissions

The manifest declares `RECEIVE_SMS`, `READ_SMS`, and
`POST_NOTIFICATIONS`. These are dangerous permissions on Android 6+
and must be granted at runtime before the SMS receiver will fire. The
onboarding flow walks the user through the consent screen before the
rest of the UI unlocks.

## Hardware acceleration (on-device backend, currently dormant)

The on-device inference engine (LiteRT-LM, not exposed in the UI today)
tries the most powerful backend first and falls through on failure:

| Order | Backend | Where it lands                       | Perf (S26 Ultra) |
------:|---------|--------------------------------------|------------------|
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
