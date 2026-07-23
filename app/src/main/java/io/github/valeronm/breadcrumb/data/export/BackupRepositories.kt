package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.TrackRepository

/** The three repositories a backup spans — one handle for both export and restore. */
class BackupRepositories(
    val tracks: TrackRepository,
    val places: PlaceRepository,
    val liveness: LivenessRepository,
)
