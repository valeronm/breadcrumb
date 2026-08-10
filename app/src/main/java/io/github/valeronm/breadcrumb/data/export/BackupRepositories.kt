package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.DerivationStore
import io.github.valeronm.breadcrumb.data.LivenessRepository
import io.github.valeronm.breadcrumb.data.PlaceRepository
import io.github.valeronm.breadcrumb.data.TrackRepository

/**
 * What a backup spans — one handle for both export and restore. [derivation] is restore's alone:
 * nothing about the stored stays is in the file (they are derived from the tracks it carries), and
 * a restore is what leaves them owed.
 */
class BackupRepositories(
    val tracks: TrackRepository,
    val places: PlaceRepository,
    val liveness: LivenessRepository,
    val derivation: DerivationStore,
)
