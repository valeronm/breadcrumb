package io.github.valeronm.breadcrumb.boundaryfixture.domain

import androidx.annotation.VisibleForTesting
import io.github.valeronm.breadcrumb.boundaryfixture.data.TrackRepository
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.Track
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.TrackDao
import io.github.valeronm.breadcrumb.boundaryfixture.location.RecorderAdapter
import io.github.valeronm.breadcrumb.boundaryfixture.ui.Screen
import android.content.Context as Host

class AliasedPlatformImport(val host: Host)

class QualifiedPlatformReference {
    fun uptimeMs(): Long = android.os.SystemClock.uptimeMillis()
}

class HandWrittenPlatformAnnotation(@VisibleForTesting val probe: Int)

class ReachesUi(val screen: Screen)

class ReachesRepository(val repository: TrackRepository)

class ReachesDao(val dao: TrackDao)

class ReachesRecorder(val recorder: RecorderAdapter)

class ReachesSharedRow(val track: Track)

class AtlasInput
