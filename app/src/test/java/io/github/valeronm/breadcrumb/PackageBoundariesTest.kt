package io.github.valeronm.breadcrumb

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.room.RoomDatabase
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.ANNOTATIONS
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
import io.github.valeronm.breadcrumb.boundaryfixture.data.TrackRepository
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.DbReachesDomain
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.DbReachesRepository
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.PlatformRow
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.RoomRow
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.TrackDao
import io.github.valeronm.breadcrumb.boundaryfixture.domain.AliasedPlatformImport
import io.github.valeronm.breadcrumb.boundaryfixture.domain.HandWrittenPlatformAnnotation
import io.github.valeronm.breadcrumb.boundaryfixture.domain.QualifiedPlatformReference
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesDao
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesRecorder
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesRepository
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesSharedRow
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesUi
import io.github.valeronm.breadcrumb.boundaryfixture.location.RecorderAdapter
import io.github.valeronm.breadcrumb.boundaryfixture.ui.Screen
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
    fun `the shared rows depend only on the standard libraries, Room's annotations and each other`() =
        sharedRowRule(APP).check(app)

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

    private fun assertRejected(rule: ArchRule, subject: KClass<*>, target: KClass<*>) {
        val details = rule.evaluate(fixture(subject)).failureReport.details
        val name = target.java.name
        assertTrue("no violation names $name:\n${details.joinToString("\n")}", details.any { name in it })
    }

    private fun fixture(type: KClass<*>): JavaClasses = fixtures.that(belongToAnyOf(type.java))

    private class PackageTree(val root: String, rows: List<String>) {
        val sharedRows = named("are shared rows", rows.map { "$root.data.db.$it" })
        val domainReach = resideInAnyPackage("java..", "kotlin..").or(sharedRows).or(COMPILER_ANNOTATIONS)
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

        val APP = PackageTree(
            "io.github.valeronm.breadcrumb",
            listOf("Place", "TrackPoint", "TrackSummary", "Track", "TrackEndpoints"),
        )
        val FIXTURE = PackageTree("${APP.root}.boundaryfixture", listOf("Track", "PlatformRow", "RoomRow"))

        // ArchUnit's `ImportOption.DoNotIncludeTests` does not match AGP's unit-test output folders.
        val app: JavaClasses by lazy {
            ClassFileImporter()
                .withImportOption(ImportOption { location -> !location.contains("UnitTest") })
                .importPackages("${APP.root}.domain", "${APP.root}.data.db")
        }

        // Each import resolves its dependencies from the classpath anew.
        val fixtures: JavaClasses by lazy { ClassFileImporter().importPackages(FIXTURE.root) }

        fun domainRule(tree: PackageTree): ArchRule =
            classes()
                .that().resideInAPackage("${tree.root}.domain..")
                .should().onlyDependOnClassesThat(
                    tree.domainReach.or(resideInAPackage("${tree.root}.domain..")),
                )

        fun databaseRule(tree: PackageTree): ArchRule =
            noClasses()
                .that().resideInAPackage("${tree.root}.data.db..")
                .should().dependOnClassesThat(
                    resideInAPackage("${tree.root}..").and(resideOutsideOfPackage("${tree.root}.data.db..")),
                )

        // The domain calls into the rows.
        fun sharedRowRule(tree: PackageTree): ArchRule =
            classes()
                .that(tree.sharedRows)
                .should().onlyDependOnClassesThat(
                    tree.domainReach.or(resideInAPackage("androidx.room..").and(ANNOTATIONS)),
                )

        fun named(description: String, names: Collection<String>): DescribedPredicate<JavaClass> =
            DescribedPredicate.describe(description) { it.name in names }
    }
}
