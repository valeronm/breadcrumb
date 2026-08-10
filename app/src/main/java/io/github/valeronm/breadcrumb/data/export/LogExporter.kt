package io.github.valeronm.breadcrumb.data.export

import android.content.Context
import android.net.Uri
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * The Logs page's Share file: the whole persisted history snapshotted into the exports dir and
 * handed back as a content Uri, following [GpxExporter]'s contract so the UI keeps only the
 * intent. A fixed name, deliberately — each share overwrites the last, so the exports dir holds
 * one log snapshot however often it is shared.
 */
object LogExporter {

    /** Blocks for the snapshot copy — call from a background dispatcher. */
    fun export(context: Context): Uri {
        val file = exportsFile(context, "breadcrumb-logs.txt")
        DebugLog.snapshotTo(file)
        return exportUri(context, file)
    }
}
