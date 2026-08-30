package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.util.SliderStops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A line break with whatever indentation surrounds it — one space's worth of separation. */
private val LINE_BREAK_RUN = Regex("[ \\t]*[\\r\\n]+[ \\t]*")

/**
 * Everything the user gets to say about one place — its name, its capture radius and its center —
 * over a full-height map of what the circle would take: this place's endpoints and the loose ones
 * around it, the named neighbors it competes with, and their areas muted underneath. Every
 * adjustment previews and none writes: the pin moves exactly as the slider moves the circle, and the
 * scan re-measures from there, so the screen always shows what saving would produce. A slider can be
 * dragged back; a jumped pin has nowhere obvious to return to, so both ways of moving it offer an
 * Undo instead.
 *
 * **The center is placed by long-pressing the map**, and the re-center action is the shortcut beside
 * it — snap to the middle of what the circle already holds. Both are needed, and in that order: the
 * center is what decides which endpoints are held at all, so an answer derived from the held ones
 * can't be the only one available. A long press rather than a tap because a tap is how a map is
 * panned; and a placement doesn't re-fit the camera, or the point aimed at would slide away under
 * the finger that aimed at it.
 *
 * **This is also where an unnamed cluster becomes a place**, which is why the name is here and not
 * only on the detail screen: a cluster has no name to identify it by, so naming it is the
 * one moment the map matters most — and the same screen then lets the radius be judged before the
 * name is committed, rather than in a second trip. Done writes name, radius and pin as one row.
 * Removing the place is its own button under the map, and a blank name is therefore never a delete —
 * Done simply disables. That separation is what the explicit button buys: the field says what a place
 * is called, and nothing about whether it exists.
 * A layer of its own above the place detail, and the map is the reason: the two screens want it at
 * different heights, and a `MapView` is a `TextureView` — handed a new size it scales its
 * last-rendered frame into the new box until it has one of its own, the pin visibly stretching to
 * an oval. Nothing fixes that inside one shared map (hiding is too late, the scaled frame was
 * rendered before the hide; animating the height makes it every frame instead of one; sequencing
 * against `OnDidFinishRenderingFrame` spares the markers but not the basemap, and costs two frames
 * of latency); a map per
 * screen is never resized, so there is nothing to hide, sequence or animate around. Its own camera
 * is the point rather than the price: this screen opens framed on the circle, in a state the radius
 * can be judged in, wherever the detail map below was panned. Backing out is the layer's own
 * predictive-back gesture, discarding by construction — radius and pin are local to a screen that
 * gets thrown away, so nothing needs restoring.
 */
@Composable
internal fun PlaceEditScreen(
    summary: PlaceResolver.PlaceSummary,
    neighbors: List<PlaceMarker>,
    candidates: List<Coordinate>,
    rivals: List<PlaceClusterer.Seed>,
    viewModel: TrackListViewModel,
    onClose: () -> Unit,
    /** The row a create landed on — the screen underneath follows the place there. */
    onCreated: (Long) -> Unit,
    /** Removes the place and leaves this screen — the caller owns both, since the Undo it offers has
     *  to outlive a layer that is going away. */
    onRemove: (Place) -> Unit,
) {
    // Null while this is still a cluster being named — every write below then becomes the one insert.
    val place = summary.place
    // Everything this screen adjusts is local until Done: the name, the circle and its center
    // preview together, and nothing is written on the way. A write here re-derives the whole
    // timeline, so committing per drag step would re-derive it several times for one adjustment.
    // Leaving without Done discards them all by simply never having written them.
    var radiusM by remember(place?.id) { mutableFloatStateOf(summary.radiusM.toFloat()) }
    // Where the place sits, which for an unnamed cluster is where naming would drop the pin rather
    // than the anchor its clustering grew from — so the circle previews what saving produces.
    var pin by remember(place?.id) { mutableStateOf(summary.pin) }
    // The state, not its value: read here and every keystroke would invalidate this whole screen —
    // including the map, which cannot skip and would re-run its input diff per character. The field
    // reads it a level down ([PlaceNameField]), the way ZoomReadout takes the camera's zoom.
    val name = remember(place?.id) { mutableStateOf(place?.label ?: "") }
    // Read where the bar's actions are built, and that scope also measures the re-center target over
    // every captured endpoint — so it must not turn on the name itself, or each keystroke would walk
    // the scan again. Derived, so it changes only as the field crosses between blank and not.
    val nameGiven by remember { derivedStateOf { name.value.isNotBlank() } }
    // Its own host, not the app's: that one hangs off MainScreen's Scaffold, under every overlay
    // layer, so an Undo offered from this screen would be covered by the screen offering it.
    val snackbarHostState = remember { SnackbarHostState() }
    val undo = rememberUndoSnackbar(snackbarHostState)
    // Both ways of moving the pin are one step back: a jumped pin has nowhere obvious to return to,
    // where a slider can simply be dragged again.
    val pinMoved = stringResource(R.string.places_pin_moved)
    val movePin: (Coordinate, String) -> Unit = { target, message ->
        val was = pin
        pin = target
        undo.show(message) { pin = was }
    }
    // Down to 25 m (75 ft, the step-aligned stop nearest it): a doorway-scale place needs a circle
    // tighter than GPS scatter, and narrowing one is also how it stops claiming a neighbour's stops.
    val radiusScale = rememberDistanceScale(SliderStops(25, 750, 25), SliderStops(75, 2475, 75))
    val maxRadiusM = radiusScale.metersOf(radiusScale.range.endInclusive).toDouble()
    // Prepared once per pin, not per drag step — whether a neighbor keeps an endpoint has nothing to
    // do with our radius, and a per-step scan made a place with a few thousand endpoints around it
    // lag under the finger (see CaptureScan). Off the main thread, because the frame it would land
    // on is this layer's opening one: a 300 ms animation and a fresh MapView loading its style, plus
    // a distance call per endpoint. Null until the first scan (the map draws plain endpoints
    // meanwhile), and the previous scan stays up while a moved pin re-measures, so re-centering
    // colors the dots from where the pin was for a frame rather than blanking them. Keyed on what
    // the scan reads, not the whole summary — its visit stats move on every derivation and would
    // rebuild this for nothing.
    val scan by produceState<PlaceClusterer.CaptureScan?>(null, pin, candidates, rivals, maxRadiusM) {
        value = withContext(Dispatchers.Default) {
            PlaceClusterer.scanCapture(
                candidates = candidates,
                anchor = pin,
                maxRadiusM = maxRadiusM,
                rivals = rivals,
                distance = AndroidDistance,
            )
        }
    }
    val captureDots = remember(scan) {
        // Conceded dots carry no distance — a nearer pin holds them at any radius, so the map
        // must draw them settled rather than compare them.
        scan?.let {
            it.winnable.map { reach -> CaptureDot(reach.location, reach.distanceM) } +
                it.conceded.map { endpoint -> CaptureDot(endpoint, null) }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Text(
                        // Titled with the action that opened it, not with what it does to a name —
                        // "Create place" is the offer the button made. Bounded here rather than moved
                        // into the content as on the detail screen: this screen's content is a
                        // full-height map with nowhere to put a heading, and you arrive already
                        // knowing which place you opened, or what you came to do to a stop.
                        place?.label ?: stringResource(R.string.places_create),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackNavIcon(onClose) },
                actions = {
                    // Measured against the circle on screen, not the one last saved: dragging
                    // changes what it takes, which moves the middle of it, which is where
                    // re-centering would put the pin. Taking the offer moves the pin there and
                    // re-measures from it, so a second tap settles rather than repeating.
                    val recenterTarget = scan?.let {
                        PlaceResolver.recenterTarget(pin, it, radiusM.toDouble(), AndroidDistance)
                    }
                    if (recenterTarget != null) {
                        val recentered = stringResource(R.string.places_pin_recentered)
                        IconButton(onClick = { movePin(recenterTarget, recentered) }) {
                            Icon(
                                Icons.Filled.FilterCenterFocus,
                                contentDescription = stringResource(R.string.places_recenter_pin),
                            )
                        }
                    }
                    // A place must be called something. Clearing the field is not how one is
                    // deleted — that offer belongs to the Remove button below.
                    SaveAction(enabled = nameGiven) {
                        viewModel.savePlace(summary, name.value, pin, radiusM.toDouble(), onCreated)
                        onClose()
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) { PlaceNameField(name) }
            Card(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibrePlaceMap(
                        center = PlaceMarker(pin, summary.place),
                        radiusM = radiusM.toDouble(),
                        endpoints = summary.endpoints,
                        neighbors = neighbors,
                        capture = captureDots,
                        rivalAreas = rivals,
                        // Placing the center by hand, where the re-center action only snaps it to
                        // what the circle already holds — and the center is what decides what is
                        // held, so it needs an answer that isn't derived from the dots. A long press
                        // rather than a tap: a tap is how a map is panned, and this is one Undo away
                        // either way.
                        onLongPress = { movePin(it, pinMoved) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Over the map's corner, not under the slider: this number is read *while*
                    // dragging, and a hand reaching down to the slider covers everything below it.
                    // Here it sits beside the dots it counts, in the one part of the screen a thumb
                    // never crosses. What the circle holds *now*, not what the stored radius holds,
                    // so it and the dots can't disagree as the slider moves.
                    val captured = remember(scan, radiusM, summary.endpoints) {
                        scan?.countWithin(radiusM.toDouble()) ?: summary.endpoints.size
                    }
                    LegendSurface(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                        Text(
                            pluralStringResource(
                                R.plurals.places_captured_endpoints,
                                captured,
                                captured,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            // Below the map, not above it: this is the one control dragged while watching the result,
            // and a hand on a slider above the map covers the circle it is sizing.
            Card(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SliderSetting(
                        stringResource(R.string.places_capture_radius),
                        radiusM.roundToInt(),
                        radiusScale,
                    ) {
                        radiusM = it.toFloat()
                    }
                }
            }
            // Under the map rather than up with the fields: removing is not one more thing to adjust,
            // and it must not sit next to the name it would once have been performed by clearing.
            // Low emphasis in the error color, and it takes effect at once with an Undo — the app
            // answers a destructive tap with a way back rather than with a question first. Nothing
            // to remove until there is a row.
            if (place != null) {
                TextButton(
                    onClick = { onRemove(place) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.places_remove))
                }
            }
        }
    }
}

/**
 * What a place is called. Takes the state rather than its value so that typing invalidates this and
 * nothing above it: the editor's column holds a map that cannot skip a recomposition, and would
 * re-run its whole input diff per character.
 */
@Composable
private fun PlaceNameField(name: MutableState<String>) {
    OutlinedTextField(
        value = name.value,
        // `singleLine` lays the field out on one line but doesn't police what arrives: paste a block
        // of text and everything past the first break is stored, and saved, where it can't be seen.
        // Breaks (and the indentation around them) fold into single spaces instead.
        onValueChange = { name.value = it.replace(LINE_BREAK_RUN, " ") },
        singleLine = true,
        label = { Text(stringResource(R.string.places_name_field)) },
        // Place names are proper nouns — capitalize each word.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
