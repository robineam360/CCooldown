package com.robin.claudeusage.ping

import android.content.Context
import com.robin.claudeusage.data.Profile
import com.robin.claudeusage.diag.AppLog

/**
 * The window-ping trace (CCRM-17), now a thin shim over the general
 * [AppLog] (CCRM-34 (Diagnostics Log)) — the ping machinery keeps its call
 * sites and shows up in the shared log under the "ping" category, at INFO,
 * exactly as before. The old standalone `ping-log.txt` is simply no longer
 * written; the shared file lives beside it in the same pullable directory.
 */
object PingLog {

    fun log(context: Context, profile: Profile?, event: String) =
        AppLog.log(context, AppLog.Level.INFO, "ping", profile, event)

    fun powerState(context: Context): String = AppLog.powerState(context)
}
