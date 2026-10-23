package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.ui.GapFixtures.detail
import io.github.valeronm.breadcrumb.ui.GapFixtures.verdict
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class GapDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val zone: ZoneId get() = timelineZone()

    private val clock: ReaderClock = readerClockOf(ApplicationProvider.getApplicationContext())

    private fun measures() = Measures(UnitSystem.METRIC, unitSymbols(context))

    private fun screen(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMeasures provides measures(),
                LocalDurationSymbols provides durationSymbols(LocalContext.current),
                LocalReaderClock provides clock,
            ) {
                AppTheme { content() }
            }
        }
    }

    // --- Suggestion ------------------------------------------------------------

    @Test
    fun `the suggestion's button names the suggested fix`() {
        screen { GapSuggestion(verdict(GapDiagnosis.Fix.MISSED_START, GapDiagnosis.Reason.PARKED_THEN_LEFT)) {} }

        compose.onNodeWithText(context.getString(R.string.gap_fix_missed_start)).assertIsDisplayed()
    }

    @Test
    fun `the suggestion's button reports the suggested fix`() {
        var picked: GapDiagnosis.Fix? = null
        screen { GapSuggestion(verdict(GapDiagnosis.Fix.MERGE, GapDiagnosis.Reason.DROPOUT)) { picked = it } }

        compose.onNodeWithText(context.getString(R.string.gap_fix_merge)).performClick()

        assertEquals(GapDiagnosis.Fix.MERGE, picked)
    }

    // --- Facts -----------------------------------------------------------------

    @Test
    fun `the facts state the speed as a floor`() {
        val apart = 1_000.0
        val mps = apart / (GapFixtures.HOUR / 1000.0)
        screen { GapFacts(detail(zone, verdict = verdict(apartMeters = apart, atLeastMps = mps)), onOpenPlace = {}) }

        val speed = measures().speedFromKmh(mps * 3.6)
        compose.onNodeWithText(context.getString(R.string.gap_at_least_speed, speed), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `an instant gap states no speed`() {
        screen {
            GapFacts(
                detail(zone, verdict = verdict(apartMeters = 50.0, atLeastMps = null)),
                onOpenPlace = {},
            )
        }

        val atLeast = context.getString(R.string.gap_at_least_speed, "")
        compose.onNodeWithText(atLeast, substring = true).assertDoesNotExist()
    }

    @Test
    fun `a gap with an unknown side states neither distance nor speed`() {
        screen { GapFacts(detail(zone, verdict = verdict(apartMeters = null, atLeastMps = null)), onOpenPlace = {}) }

        val atLeast = context.getString(R.string.gap_at_least_speed, "")
        compose.onNodeWithText(atLeast, substring = true).assertDoesNotExist()
    }
}
