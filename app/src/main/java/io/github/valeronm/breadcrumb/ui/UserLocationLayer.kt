package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Feature as GeoFeature

private const val USER_LOCATION_SOURCE = "user-location-src"
private const val USER_LOCATION_LAYER = "user-location-layer"

/** The zoom a "go to my location" lands on at least — near enough to pick a house by. */
internal const val USER_LOCATION_ZOOM = 17.0

/** The phone's position, styled as the platform's blue dot. Add it after every other layer: it is
 *  the one mark on a map that is not the history. */
internal fun addUserLocationLayer(style: Style, at: Coordinate?) {
    style.addSource(GeoJsonSource(USER_LOCATION_SOURCE, userLocationFeatures(at)))
    style.addLayer(
        CircleLayer(USER_LOCATION_LAYER, USER_LOCATION_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
}

internal fun updateUserLocation(style: Style, at: Coordinate?) {
    style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE)?.setGeoJson(userLocationFeatures(at))
}

/** Puts the camera on [at], zooming in to [USER_LOCATION_ZOOM] but never out from nearer. */
internal fun moveCameraTo(map: MapLibreMap, at: Coordinate) {
    val zoom = maxOf(map.cameraPosition.zoom, USER_LOCATION_ZOOM)
    map.moveCamera(CameraUpdateFactory.newLatLngZoom(at.toLatLng(), zoom))
}

private fun userLocationFeatures(at: Coordinate?): FeatureCollection =
    FeatureCollection.fromFeatures(
        listOfNotNull(at?.let { GeoFeature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }),
    )
