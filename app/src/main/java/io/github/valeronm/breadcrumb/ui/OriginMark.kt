package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.export.GoogleTimelineImporter
import io.github.valeronm.breadcrumb.domain.TrackOrigin

/** Where a trip or a place came from, when not from this app: the source's name and its glyph. */
internal enum class OriginMark(@StringRes val labelRes: Int, val icon: ImageVector) {
    GPX(R.string.common_origin_gpx, Icons.Filled.UploadFile),
    GOOGLE_TIMELINE(R.string.common_origin_google_timeline, Icons.Filled.History),
    ENTERED_BY_HAND(R.string.common_origin_entered_by_hand, Icons.Filled.TouchApp),
}

/** Null for a recorded trip, which is what a trip is unless something says otherwise. */
internal val TrackOrigin.mark: OriginMark?
    get() = when (this) {
        TrackOrigin.RECORDED -> null
        TrackOrigin.IMPORTED -> OriginMark.GPX
        TrackOrigin.GOOGLE_TIMELINE -> OriginMark.GOOGLE_TIMELINE
        TrackOrigin.MANUAL -> OriginMark.ENTERED_BY_HAND
    }

/** Null for a place the user made; an import's rows name their source in [Place.externalProvider]. */
internal val Place.mark: OriginMark?
    get() = when (externalProvider) {
        GoogleTimelineImporter.PROVIDER -> OriginMark.GOOGLE_TIMELINE
        else -> null
    }

@Composable
internal fun OriginGlyph(mark: OriginMark, modifier: Modifier = Modifier) {
    Icon(
        mark.icon,
        contentDescription = stringResource(mark.labelRes),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(16.dp),
    )
}

@Composable
internal fun OriginCaption(mark: OriginMark?) {
    mark ?: return
    Text(
        stringResource(mark.labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
