package com.spendai.app.domain.backup

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Kills and relaunches the app process. Required after
 * [FullBackupManager.restore] because `SpendAiApp` is a hand-rolled
 * service locator of `by lazy` singletons (the DB, every
 * repository, every agent) that are fixed for the process's
 * lifetime — there is no way to hot-swap them onto the newly
 * restored database file. A fresh process is the only way every
 * one of those singletons re-reads the file that now exists on
 * disk.
 */
object AppRestarter {

    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        context.startActivity(intent)
        // startActivity() is a synchronous Binder call into system_server, so the
        // relaunch is already queued by the time it returns. The short delay before
        // killing this process is defensive insurance against OEM AMS scheduling
        // quirks on some devices, not a correctness requirement.
        Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 300)
    }
}
