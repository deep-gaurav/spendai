<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" alt="SpendAI logo">
</p>

<h1 align="center">SpendAI</h1>

<p align="center">
  Private, on-device expense tracking for Android.<br>
  Financial SMS in &rarr; a three-agent LLM pipeline &rarr; structured ledger + an "Ask AI" chat over your spending.
</p>

<p align="center">
  <a href="../../actions/workflows/build.yml"><img src="../../actions/workflows/build.yml/badge.svg" alt="CI"></a>
  <a href="../../releases/tag/rolling"><img alt="rolling release" src="https://img.shields.io/badge/download-rolling_APK-FFB300?logo=android&logoColor=white"></a>
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-GPLv3-blue"></a>
</p>

---

## Download

The latest build is published automatically to the **rolling release**, which is recreated on every successful `master` push.

**Direct APK:** [`spendai.apk`](../../releases/download/rolling/spendai.apk) &mdash; debug-signed, minified.

```sh
adb install -r spendai.apk
```

> The rolling build is debug-signed for convenience. For a properly signed release, push a `v*` tag after configuring the signing secrets in [CI &amp; signing](#ci--signing).

## What it does

- **SMS pipeline** &mdash; every incoming financial SMS runs through three agents: A1 parses, A2 resolves merchants/accounts (plus your "this is me" and category hints), A3 audits against recent activity to dedupe, link transfers/refunds, and correct mistakes.
- **Insights** &mdash; KPIs, category donut, daily trend, top merchants, day-of-week, all hand-rolled Compose charts. Self-transfers and self-flagged merchants are excluded by default.
- **Ask AI** &mdash; multi-turn chat with two tools: read-only SQL over your ledger, and an allowlisted merchant-knowledge mutator. Reasoning-model friendly (strips thinking blocks, retries parse failures, optional model-as-judge verifier).
- **Merchant knowledge** &mdash; mark counterparties as "me", add notes / category hints via the Merchants screen or conversationally. Both feed the same mutator and trigger A3 reprompts.
- **Edit / Review / Debug log** &mdash; full audit trail and manual correction.

The model runs against an LLM API **you** configure (Gemini, OpenAI-compatible, Anthropic, ZHIPU, Ollama, or a Custom endpoint) from in-app Model settings. Nothing is hard-coded; your keys stay on your device.

## Quick start

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK platform 35 and JDK 17+. The app opens on Home; the overflow menu exposes Sources &amp; categories, Merchants, Debug log, and Model settings. Onboarding walks you through SMS permissions and model configuration.

### Model setup

Pick a backend in **Model settings**, paste the API key (+ optional base URL + model name), and tap **Probe**. Google Gemini in AI Studio (`gemma-4-31b-it`) is a free option. The model needs a 64K+ context window for daily ledger grouping.

## Architecture

```
SMS -> A1 (parse) -> A2 (resolve + isSelf/metadata) -> A3 (audit + link)
                                                        |
                                                        v
                                          Room: spend_transaction
Ask AI chat ---------------- read-only SQL --------------+
mutate_merchant ----------- MerchantMutator -> merchant / merchant_metadata + reprompt_job
```

Full agent + Ask-AI design lives in [AGENTS.md](AGENTS.md). Source is under `app/src/main/java/com/spendai/app/` (`data/`, `domain/agent/`, `inference/`, `ui/`).

## CI &amp; signing

[`.github/workflows/build.yml`](.github/workflows/build.yml) builds debug + release APK on every push/PR, publishes the rolling release on `master`, and (on a `v*` tag with secrets) a signed versioned release. The release signing key lives entirely in GitHub secrets &mdash; no key material is committed.

One-time signing setup:

```sh
./scripts/generate-signing-key.sh        # spendai-release.jks, CN=Deep, C=IN
base64 -w 0 spendai-release.jks          # paste into the secret below
```

Add these repository secrets (Settings &rarr; Secrets and variables &rarr; Actions):

1. `SPENDAI_SIGNING_KEY_BASE64` &mdash; the base64 blob above
2. `SPENDAI_SIGNING_STORE_PASSWORD` &mdash; your `-storepass`
3. `SPENDAI_SIGNING_KEY_ALIAS` &mdash; defaults to `spendai`
4. `SPENDAI_SIGNING_KEY_PASSWORD` &mdash; your `-keypass`

Then `git tag v0.1 && git push --tags` to ship a signed release.

## Permissions

`RECEIVE_SMS`, `READ_SMS`, and `POST_NOTIFICATIONS` are runtime-permission gated; onboarding walks through consent before the rest of the app unlocks.

## License

GNU General Public License v3.0 &mdash; see [LICENSE](LICENSE).
