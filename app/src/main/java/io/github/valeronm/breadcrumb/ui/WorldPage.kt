package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.valeronm.breadcrumb.domain.VisitedWorld
import io.github.valeronm.breadcrumb.domain.atlas.AtlasCity

@Composable
internal fun WorldPage(
    viewModel: TrackListViewModel,
    onOpenData: () -> Unit,
    onOpenCity: (AtlasCity) -> Unit,
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
internal fun CountryList(countries: List<VisitedWorld.Country>, onOpenCity: (AtlasCity) -> Unit) {
    val cityCount = countries.sumOf { it.cities.size }
    // Kept across rotation and paging away.
    var open by rememberSaveable { mutableStateOf(listOf<String>()) }
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item(key = "totals") {
            StatHeaderRow(
                stringResource(R.string.insights_world_stat_countries) to "${countries.size}",
                stringResource(R.string.insights_world_stat_cities) to "$cityCount",
            )
        }
        countries.forEachIndexed { index, country ->
            val isOpen = country.code in open
            val shape = groupedRowShape(index, countries.size)
            stickyHeading(country.code, PaddingValues(top = 2.dp)) {
                CountryCard(country, isOpen, if (isOpen) cardAboveBand(shape) else shape) {
                    // The list keeps its place by its first visible row, which closing a pinned card removes.
                    val cardIndex = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == country.code }?.index
                    if (isOpen && cardIndex != null && list.firstVisibleItemIndex > cardIndex) list.requestScrollToItem(cardIndex)
                    open = if (isOpen) open - country.code else open + country.code
                }
            }
            if (isOpen) {
                itemsIndexed(country.cities, key = { _, city -> "city:${city.city.row}" }) { cityIndex, city ->
                    ListRowBandItem(
                        cityIndex,
                        country.cities.size,
                        shape,
                        onClick = { onOpenCity(city.city) },
                        onClickLabel = stringResource(R.string.insights_world_show_on_map),
                    ) { CityRow(city) }
                }
            }
        }
    }
}

@Composable
private fun CountryCard(
    country: VisitedWorld.Country,
    isOpen: Boolean,
    shape: RoundedCornerShape,
    onToggle: () -> Unit,
) {
    val code = country.code
    val cityCount = country.cities.size
    val toggleLabel = stringResource(if (isOpen) R.string.insights_world_hide_cities else R.string.insights_world_show_cities)
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
        onClick = if (cityCount > 0) onToggle else null,
        onClickLabel = toggleLabel,
        trailing = if (cityCount > 0) {
            { ListRowExpandMark(isOpen) }
        } else {
            null
        },
    )
}

@Composable
private fun RowScope.CityRow(city: VisitedWorld.City) {
    Text(
        listedCityName(city),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
    )
    Text(
        pluralStringResource(R.plurals.insights_world_days, city.days, city.days),
        // Equal-width digits line the day counts up down the column.
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
