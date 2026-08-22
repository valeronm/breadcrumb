package io.github.valeronm.breadcrumb.util

import io.github.valeronm.breadcrumb.BuildConfig

/**
 * What a build is, as facts that vary independently and are combined only where something is
 * displayed: the version it declares, the variant it was built as, and the commit it came from.
 *
 * They are kept apart because each answers a different question and each moves on its own — a
 * version is bumped by hand for an upload, a variant is chosen by the Gradle task, and a commit
 * changes with every edit. Folding one into another (a variant suffix on the version, say) makes a
 * combined string the only form either is available in, which is how the variant came to be stated
 * twice in one log line.
 *
 * A `versionCode` is deliberately absent: it is Play's upload counter, it names nothing a reader or
 * a log line asks about, and every build between two uploads carries the same one.
 */
internal object BuildIdentity {

    /** The marketing version alone — no variant, no commit. */
    val version: String = BuildConfig.VERSION_NAME

    /** The variant, null for the build that ships: a release states no variant, it is the default. */
    val variant: String? = BuildConfig.BUILD_TYPE.takeUnless { it == "release" }

    /** The commit, with `-dirty` when the build came from uncommitted changes. */
    val commit: String = BuildConfig.GIT_SHA

    /** For a reader: what the app is. The commit is left out, being a hash they can do nothing with. */
    val shown: String = if (variant == null) version else "$version $variant"

    /**
     * For a log line: what a reader is shown, plus the commit they aren't. The variant earns its
     * place here because builds that install over one another share a package and a version, and
     * nothing else in a log separates them.
     */
    val logged: String = "$shown $commit"
}
