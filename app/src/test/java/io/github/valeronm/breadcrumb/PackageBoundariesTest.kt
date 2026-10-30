package io.github.valeronm.breadcrumb

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.room.RoomDatabase
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.ANNOTATIONS
import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.valeronm.breadcrumb.boundaryfixture.data.TrackPolicy
import io.github.valeronm.breadcrumb.boundaryfixture.data.TrackRepository
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.DbReachesDomain
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.DbReachesRepository
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.PlatformRow
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.RoomRow
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.TrackDao
import io.github.valeronm.breadcrumb.boundaryfixture.domain.AliasedPlatformImport
import io.github.valeronm.breadcrumb.boundaryfixture.domain.AtlasInput
import io.github.valeronm.breadcrumb.boundaryfixture.domain.HandWrittenPlatformAnnotation
import io.github.valeronm.breadcrumb.boundaryfixture.domain.QualifiedPlatformReference
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesDao
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesRecorder
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesRepository
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesSharedRow
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesUi
import io.github.valeronm.breadcrumb.boundaryfixture.domain.atlas.AtlasReachesDomain
import io.github.valeronm.breadcrumb.boundaryfixture.domain.atlas.AtlasValue
import io.github.valeronm.breadcrumb.boundaryfixture.history.HistoryReachesData
import io.github.valeronm.breadcrumb.boundaryfixture.history.HistoryReachesRecorder
import io.github.valeronm.breadcrumb.boundaryfixture.history.HistoryValue
import io.github.valeronm.breadcrumb.boundaryfixture.location.CoreEffect
import io.github.valeronm.breadcrumb.boundaryfixture.location.CoreReachesAdapter
import io.github.valeronm.breadcrumb.boundaryfixture.location.CoreValue
import io.github.valeronm.breadcrumb.boundaryfixture.location.RecorderAdapter
import io.github.valeronm.breadcrumb.boundaryfixture.ui.Screen
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackStats
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.location.ActivityIngest
import io.github.valeronm.breadcrumb.location.ActivitySettings
import io.github.valeronm.breadcrumb.location.DepartureTriggers
import io.github.valeronm.breadcrumb.location.Effect
import io.github.valeronm.breadcrumb.location.Fix
import io.github.valeronm.breadcrumb.location.FixIngest
import io.github.valeronm.breadcrumb.location.GateState
import io.github.valeronm.breadcrumb.location.GnssState
import io.github.valeronm.breadcrumb.location.IngestSettings
import io.github.valeronm.breadcrumb.location.Ingested
import io.github.valeronm.breadcrumb.location.Registration
import io.github.valeronm.breadcrumb.location.ResumeSignal
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

// An inlined constant or function leaves no reference to its owner in the class files these rules read.
class PackageBoundariesTest {

    @Test
    fun `domain depends only on the standard libraries, itself and the shared rows`() =
        domainRule(APP).check(app)

    @Test
    fun `the database package depends on no other package of the app`() =
        databaseRule(APP).check(app)

    @Test
    fun `the shared rows depend only on what domain may and Room's annotations`() =
        sharedRowRule(APP).check(app)

    @Test
    fun `the recorder core depends only on what domain may, the track policy and itself`() =
        recorderCoreRule(APP).check(app)

    @Test
    fun `the atlas depends only on the standard libraries, itself and its domain inputs`() =
        atlasRule(APP).check(app)

    @Test
    fun `the history reader depends only on what domain may, coroutines and itself`() =
        historyRule(APP).check(app)

    @Test
    fun `the domain rule rejects an aliased platform import`() =
        assertRejected(domainRule(FIXTURE), AliasedPlatformImport::class, Context::class)

    @Test
    fun `the domain rule rejects a fully qualified platform reference`() =
        assertRejected(domainRule(FIXTURE), QualifiedPlatformReference::class, SystemClock::class)

    @Test
    fun `the domain rule rejects a hand-written platform annotation`() =
        assertRejected(domainRule(FIXTURE), HandWrittenPlatformAnnotation::class, VisibleForTesting::class)

    @Test
    fun `the domain rule rejects the ui`() =
        assertRejected(domainRule(FIXTURE), ReachesUi::class, Screen::class)

    @Test
    fun `the domain rule rejects a repository`() =
        assertRejected(domainRule(FIXTURE), ReachesRepository::class, TrackRepository::class)

    @Test
    fun `the domain rule rejects a dao`() =
        assertRejected(domainRule(FIXTURE), ReachesDao::class, TrackDao::class)

    @Test
    fun `the domain rule rejects a recorder adapter`() =
        assertRejected(domainRule(FIXTURE), ReachesRecorder::class, RecorderAdapter::class)

    @Test
    fun `the domain rule accepts a shared row`() =
        domainRule(FIXTURE).check(fixture(ReachesSharedRow::class))

    @Test
    fun `the database rule rejects domain`() =
        assertRejected(databaseRule(FIXTURE), DbReachesDomain::class, ReachesSharedRow::class)

    @Test
    fun `the database rule rejects a repository`() =
        assertRejected(databaseRule(FIXTURE), DbReachesRepository::class, TrackRepository::class)

    @Test
    fun `the database rule accepts the platform and its own package`() =
        databaseRule(FIXTURE).check(fixture(TrackDao::class))

    @Test
    fun `the shared row rule rejects the platform`() =
        assertRejected(sharedRowRule(FIXTURE), PlatformRow::class, Context::class)

    @Test
    fun `the shared row rule rejects Room beyond its annotations`() =
        assertRejected(sharedRowRule(FIXTURE), RoomRow::class, RoomDatabase::class)

    @Test
    fun `the recorder core rule rejects the platform from a nested class`() =
        assertRejected(recorderCoreRule(FIXTURE), CoreEffect::class, Context::class)

    @Test
    fun `the recorder core rule rejects an app class outside its allowlist`() =
        assertRejected(recorderCoreRule(FIXTURE), CoreReachesAdapter::class, RecorderAdapter::class)

    @Test
    fun `the recorder core rule accepts the domain, a shared row, the track policy and itself`() =
        recorderCoreRule(FIXTURE).check(fixture(CoreValue::class))

    @Test
    fun `the atlas rule rejects a domain class outside its inputs`() =
        assertRejected(atlasRule(FIXTURE), AtlasReachesDomain::class, ReachesSharedRow::class)

    @Test
    fun `the atlas rule accepts its domain inputs, itself and the standard libraries`() =
        atlasRule(FIXTURE).check(fixture(AtlasValue::class))

    @Test
    fun `the history rule accepts the domain, coroutines and itself`() =
        historyRule(FIXTURE).check(fixture(HistoryValue::class))

    @Test
    fun `the history rule rejects a repository`() =
        assertRejected(historyRule(FIXTURE), HistoryReachesData::class, TrackRepository::class)

    @Test
    fun `the history rule rejects a recorder adapter`() =
        assertRejected(historyRule(FIXTURE), HistoryReachesRecorder::class, RecorderAdapter::class)

    private fun assertRejected(rule: ArchRule, subject: KClass<*>, target: KClass<*>) {
        val details = rule.evaluate(fixture(subject)).failureReport.details
        val name = target.java.name
        assertTrue("no violation names $name:\n${details.joinToString("\n")}", details.any { name in it })
    }

    private fun fixture(type: KClass<*>): JavaClasses = fixtures.that(belongToAnyOf(type.java))

    private class PackageTree(
        val root: String,
        rows: List<String>,
        cores: List<KClass<*>>,
        policy: List<KClass<*>>,
        atlasInputs: List<KClass<*>>,
    ) {
        val domain = resideInAPackage("$root.domain..")
        val sharedRows = named("are shared rows", rows.map { "$root.data.db.$it" })
        val domainReach = STANDARD.or(domain).or(sharedRows)
        val recorderCore = members("the recorder core", cores)
        val trackPolicy = members("the track policy", policy)
        val atlas = resideInAPackage("$root.domain.atlas..")
        val atlasReach = STANDARD.or(atlas).or(members("the atlas's domain inputs", atlasInputs))
        val history = resideInAPackage("$root.history..")
        val historyReach = domainReach.or(history).or(resideInAPackage("kotlinx.coroutines.."))
    }

    private companion object {
        val COMPILER_ANNOTATIONS = named(
            "are compiler-added annotations",
            setOf(
                "org.jetbrains.annotations.NotNull",
                "org.jetbrains.annotations.Nullable",
                // The Compose compiler runs over the whole module.
                "androidx.compose.runtime.internal.StabilityInferred",
            ),
        )

        val STANDARD = resideInAnyPackage("java..", "kotlin..").or(COMPILER_ANNOTATIONS)

        val APP = PackageTree(
            "io.github.valeronm.breadcrumb",
            rows = listOf("Place", "TrackPoint", "TrackSummary", "Track", "TrackEndpoints"),
            cores = listOf(
                FixIngest::class,
                ActivityIngest::class,
                Effect::class,
                ResumeSignal::class,
                ActivitySettings::class,
                DepartureTriggers::class,
                Fix::class,
                GateState::class,
                GnssState::class,
                Ingested::class,
                IngestSettings::class,
                Registration::class,
            ),
            // The track policy defaults its distance to AndroidDistance, which no rule here checks.
            policy = listOf(TrackQuality::class, TrackStats::class),
            atlasInputs = listOf(DistanceFn::class, PlaceSearch::class),
        )
        val FIXTURE = PackageTree(
            "${APP.root}.boundaryfixture",
            rows = listOf("Track", "PlatformRow", "RoomRow"),
            cores = listOf(CoreEffect::class, CoreValue::class, CoreReachesAdapter::class),
            policy = listOf(TrackPolicy::class),
            atlasInputs = listOf(AtlasInput::class),
        )

        // ArchUnit's `ImportOption.DoNotIncludeTests` does not match AGP's unit-test output folders.
        val app: JavaClasses by lazy {
            ClassFileImporter()
                .withImportOption(ImportOption { location -> !location.contains("UnitTest") })
                .importPackages("${APP.root}.domain", "${APP.root}.data.db", "${APP.root}.location", "${APP.root}.history")
        }

        // Each import resolves its dependencies from the classpath anew.
        val fixtures: JavaClasses by lazy { ClassFileImporter().importPackages(FIXTURE.root) }

        fun domainRule(tree: PackageTree): ArchRule = onlyReach(tree.domain, tree.domainReach)

        fun databaseRule(tree: PackageTree): ArchRule =
            noClasses()
                .that().resideInAPackage("${tree.root}.data.db..")
                .should().dependOnClassesThat(
                    resideInAPackage("${tree.root}..").and(resideOutsideOfPackage("${tree.root}.data.db..")),
                )

        // The domain calls into the rows.
        fun sharedRowRule(tree: PackageTree): ArchRule =
            onlyReach(
                tree.sharedRows,
                tree.domainReach.or(resideInAPackage("androidx.room..").and(ANNOTATIONS)),
            )

        fun recorderCoreRule(tree: PackageTree): ArchRule =
            onlyReach(
                tree.recorderCore,
                tree.domainReach.or(tree.recorderCore).or(tree.trackPolicy),
            )

        fun atlasRule(tree: PackageTree): ArchRule = onlyReach(tree.atlas, tree.atlasReach)

        fun historyRule(tree: PackageTree): ArchRule = onlyReach(tree.history, tree.historyReach)

        // An array carries neither its element's name nor its enclosing class.
        fun onlyReach(
            subjects: DescribedPredicate<in JavaClass>,
            allowed: DescribedPredicate<in JavaClass>,
        ): ArchRule =
            classes().that(subjects).should().onlyDependOnClassesThat(
                DescribedPredicate.describe<JavaClass>(allowed.description) { allowed.test(it.baseComponentType) },
            )

        fun named(description: String, names: Collection<String>): DescribedPredicate<JavaClass> =
            DescribedPredicate.describe(description) { it.name in names }

        fun members(description: String, types: List<KClass<*>>): DescribedPredicate<JavaClass> =
            belongTo(named(description, types.map { it.java.name }))
    }
}
