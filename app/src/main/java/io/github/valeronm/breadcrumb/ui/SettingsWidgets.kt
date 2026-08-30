package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.util.DistanceSliderScale
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.snapToStep

/** Settings-style switch with a check/cross icon in the thumb mirroring its state. */
@Composable
internal fun IconSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        // The check reads as the track showing through the thumb, which is the one pairing every
        // palette keeps contrastive. The default `onPrimaryContainer` assumes a dark theme's
        // containers are dark tones; a scheme that inverts them renders the glyph on a thumb of
        // near-identical luminance.
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
        thumbContent = {
            Icon(
                if (checked) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

/**
 * Android-settings-style group: each row is its own card, large corners on the group's outer
 * edges and small ones between neighbors (same look as the track list's day groups).
 */
@Composable
internal fun GroupedRows(vararg rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            Card(modifier = Modifier.fillMaxWidth(), shape = groupedRowShape(index, rows.size)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { row() }
            }
        }
    }
}

/** Settings-style navigation row: label + chevron, opening a stacked screen. */
@Composable
internal fun NavRow(
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // No vertical padding of its own: the GroupedRows card already pads the row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * One settings value on this screen: a local mirror of the pref plus its persist and default, so a
 * section's canReset/reset derive from the prefs themselves instead of hand-listing every write.
 */
internal class Pref<T>(initial: T, val default: T, private val persist: (T) -> Unit) {
    var value by mutableStateOf(initial)
        private set

    val isDefault: Boolean get() = value == default

    fun set(newValue: T) {
        value = newValue
        persist(newValue)
    }

    fun reset() = set(default)
}

@Composable
internal fun <T> rememberPref(default: T, load: () -> T, save: (T) -> Unit): Pref<T> =
    remember { Pref(load(), default, save) }

/**
 * Settings row with a title, explanatory subtitle and an [IconSwitch]. A row the device can't
 * currently satisfy stays visible with its switch disabled and the precondition in the subtitle —
 * the same shape `KeepScreenOnRow` uses for "available while charging".
 */
@Composable
internal fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        IconSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    /** Composable: the step's wording comes from resources, which a plain lambda cannot reach. */
    valueText: @Composable (Float) -> String,
    onChange: (Float) -> Unit,
) = LabeledSlider(label, valueText(value), value, range) { raw ->
    onChange(snapToStep(raw, step, range))
}

/** The current unit system's scale for a distance slider, cached until the units change. */
@Composable
internal fun rememberDistanceScale(
    metric: SliderStops,
    feet: SliderStops,
    zeroIsOff: Boolean = false,
): DistanceSliderScale {
    val measures = LocalMeasures.current
    val off = stringResource(R.string.common_off).takeIf { zeroIsOff }
    return remember(measures, off) { measures.sliderScale(metric, feet, off) }
}

/**
 * A distance slider riding a [DistanceSliderScale]: it drags and labels in the scale's display
 * unit (round feet for imperial users), storing meters only on commit.
 */
@Composable
internal fun SliderSetting(
    label: String,
    meters: Int,
    scale: DistanceSliderScale,
    onChange: (Int) -> Unit,
) {
    val display = scale.displayOf(meters)
    LabeledSlider(label, scale.label(display), display, scale.range) { raw ->
        onChange(scale.metersOf(scale.snap(raw)))
    }
}

/** The one label-row-plus-slider layout every slider setting renders. */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

/**
 * A settings slider's step. Spelled-out units, unlike the timeline's ladder: a slider label has
 * room for a word where a stat cell does not.
 */
@Composable
@ReadOnlyComposable
internal fun durationSettingLabel(sec: Int): String = when {
    sec <= 0 -> stringResource(R.string.common_off)
    sec < 60 -> stringResource(R.string.duration_seconds_step, sec)
    sec % 60 == 0 -> stringResource(R.string.duration_minutes_step, sec / 60)
    else -> stringResource(R.string.duration_minutes_seconds_step, sec / 60, sec % 60)
}
