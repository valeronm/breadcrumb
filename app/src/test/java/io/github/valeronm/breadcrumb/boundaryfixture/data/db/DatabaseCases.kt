package io.github.valeronm.breadcrumb.boundaryfixture.data.db

import android.content.Context
import androidx.room.RoomDatabase
import io.github.valeronm.breadcrumb.boundaryfixture.data.TrackRepository
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesSharedRow

class Track

class PlatformRow(val context: Context)

class RoomRow(val database: RoomDatabase)

class TrackDao(val context: Context, val track: Track)

class DbReachesDomain(val domain: ReachesSharedRow)

class DbReachesRepository(val repository: TrackRepository)
