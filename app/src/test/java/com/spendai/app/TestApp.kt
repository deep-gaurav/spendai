package com.spendai.app

import android.app.Application

/**
 * Test-only [Application] replacement used by Robolectric tests so we
 * do not trigger the production [SpendAiApp.onCreate] path (which
 * enqueues a WorkManager job). The class is package-private to
 * `com.spendai.app` so Robolectric's [org.robolectric.annotation.Config]
 * can find it.
 */
class TestApp : Application()
