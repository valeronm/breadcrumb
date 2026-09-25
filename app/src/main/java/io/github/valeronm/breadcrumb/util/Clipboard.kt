package io.github.valeronm.breadcrumb.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.getSystemService

/** Toasts [copied] only below Android 13, which confirms a copy with its own overlay. */
internal fun Context.copyToClipboard(text: String, @StringRes copied: Int) {
    getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(text, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, copied, Toast.LENGTH_SHORT).show()
    }
}
