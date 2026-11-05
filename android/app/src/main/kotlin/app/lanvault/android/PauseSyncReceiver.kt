package app.lanvault.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lanvault.core.sync.*

class PauseSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoSyncSettings(context).enabled = false
        SyncScheduler.cancel(context)
        SyncService.stop(context)
        SyncRuntime.update(
            SyncRuntime.state.value.copy(running = false, phase = "PAUSED", error = null)
        )
    }
}
