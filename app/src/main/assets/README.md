# Model assets directory

This directory is reserved for the Gemma 4 E2B model binary. In Phase 1 the
binary is **not** bundled with the APK — it is 2.58 GB and would balloon the
install size and break the FOSS sideload distribution model.

## Where the model comes from

The Hugging Face community publishes LiteRT-LM-ready builds:

- <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm>

Download the `.litertlm` file (typically `gemma-4-e2b-it.litertlm`, ~2.58 GB)
from that page.

## Sideload workflow

The recommended developer flow is to push the model directly to the app's
internal storage on a connected device:

```sh
# Plug in a device with USB debugging enabled, then:
adb push gemma-4-e2b-it.litertlm \
  /sdcard/Android/data/com.spendai.app/files/models/
```

On first launch the `ModelInstaller` will detect the file in
`$filesDir/models/` and load it. No copy from `assets/` happens in the
production path.

## Dev-only fallback

If you want to test the assets→filesDir copy path (e.g. in CI), drop the
`.litertlm` file here and remove the `.gitkeep` placeholder. Note that
doing so will check a 2.58 GB binary into your working tree — almost
never what you want.
