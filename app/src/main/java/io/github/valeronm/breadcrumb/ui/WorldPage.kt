package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.VisitedWorld

@Composable
internal fun WorldPage(
    viewModel: TrackListViewModel,
    onOpenData: () -> Unit,
    onOpenCity: (TravelNaming.SpentCity) -> Unit,
) {
    val countries = viewModel.world.collectAsStateWithLifecycle().value
    when {
        countries == null -> DerivingState(Modifier.fillMaxSize())
        countries.isEmpty() -> EmptyState(
            stringResource(R.string.insights_world_empty),
            Modifier.fillMaxSize().padding(32.dp),
        ) {
            RestoreOrImportButton(viewModel, onOpenData)
        }
        else -> CountryList(countries, onOpenCity)
    }
}

@Composable
internal fun CountryList(countries: List<VisitedWorld.Country>, onOpenCity: (TravelNaming.SpentCity) -> Unit) {
    val cityCount = countries.sumOf { it.cities.size }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "totals") {
            StatHeaderRow(
                stringResource(R.string.insights_world_stat_countries) to "${countries.size}",
                stringResource(R.string.insights_world_stat_cities) to "$cityCount",
            )
        }
        itemsIndexed(countries, key = { _, country -> country.code }) { index, country ->
            // Kept by the item's key across rotation and paging away.
            var isOpen by rememberSaveable { mutableStateOf(false) }
            CountryCard(country, isOpen, groupedRowShape(index, countries.size), { isOpen = !isOpen }, onOpenCity)
        }
    }
}

@Composable
private fun CountryCard(
    country: VisitedWorld.Country,
    isOpen: Boolean,
    shape: RoundedCornerShape,
    onToggle: () -> Unit,
    onOpenCity: (TravelNaming.SpentCity) -> Unit,
) {
    val code = country.code
    val cityCount = country.cities.size
    ListRowCard(
        shape = shape,
        leading = { Text(flagOf(code), fontSize = 24.sp) },
        title = countryDisplayName(code),
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitle = AnnotatedString(
            listOfNotNull(
                pluralStringResource(R.plurals.insights_world_days, country.days, country.days),
                if (cityCount > 0) pluralStringResource(R.plurals.insights_cities, cityCount, cityCount) else null,
            ).joinToString(" · "),
        ),
        trailing = if (cityCount > 0) {
            {
                ListRowExpandToggle(
                    isOpen,
                    stringResource(if (isOpen) R.string.insights_world_hide_cities else R.string.insights_world_show_cities),
                    onToggle,
                )
            }
        } else {
            null
        },
        below = if (isOpen) {
            {
                ListRowBand(
                    country.cities,
                    onClick = { onOpenCity(TravelNaming.SpentCity(it.name, code)) },
                    onClickLabel = stringResource(R.string.insights_world_show_on_map),
                ) { _, city ->
                    Text(city.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        pluralStringResource(R.plurals.insights_world_days, city.days, city.days),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The Places tab's own pin, for where a tap on the row goes.
                    ListRowEndColumn {
                        Icon(
                            Icons.Outlined.Place,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            null
        },
    )
}
