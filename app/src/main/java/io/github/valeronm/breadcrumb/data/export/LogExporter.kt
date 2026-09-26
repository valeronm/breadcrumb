package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * The Logs page's Share file: the whole persisted history snapshotted into the exports dir and
 * handed back as a content Uri, following [GpxExporter]'s contract so the UI keeps only the
 * intent.
 */
object LogExporter {

    private const val PREFIX = "breadcrumb-logs"

    /** Blocks for the snapshot copy — call from a background dispatcher. */
    fun export(context: Context): Uri {
        // Each snapshot is the whole log, so the ones already shared are dead weight in app storage.
        exportsDir(context).listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { it.delete() }
        val file = exportsFile(context, fileName(phoneName(context), System.currentTimeMillis()))
        DebugLog.snapshotTo(file)
        return exportUri(context, file)
    }

    /** Some manufacturers keep the user's device name under a key of their own, leaving this one unset. */
    private fun phoneName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    internal fun fileName(phone: String, now: Long): String {
        val safe = phone.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        val stamp = exportFileStamp(now)
        return if (safe.isEmpty()) "$PREFIX-$stamp.txt" else "$PREFIX-$safe-$stamp.txt"
    }
}
