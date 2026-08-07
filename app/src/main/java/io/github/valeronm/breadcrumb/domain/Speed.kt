package io.github.valeronm.breadcrumb.domain

/**
 * A speed, in no particular unit until someone asks for one.
 *
 * The recorder grew two speed vocabularies for a reason, and this is what reconciles them without
 * flattening either. Rules are **asserted** in km/h — a ceiling per activity is a human claim about
 * a mode of travel, and nobody writes "a car cannot exceed 61 m/s". Speeds are **measured** in m/s,
 * because they fall out of metres over milliseconds and converting at every expression would put a
 * factor in arithmetic that does not need one. Both are the same quantity, so both are this.
 *
 * What it buys is not the missing suffix. Comparing a measured speed against a stated ceiling used
 * to be two `Double`s matched by naming convention — `speedKmh > groupCeilingKmh` is correct only
 * because both ends happen to agree — and [Comparable] now makes the compiler keep that promise.
 *
 * **Deliberately absent from the point walk.** Per-fix speeds stay primitive: a Room column, a
 * `Fix`'s field, the `FloatArray` the colour ramp is built from. Those are data rather than
 * decisions, they run to millions of rows, and boxing them buys nothing — the same reasoning that
 * keeps the domain reading Room entities directly.
 */
@JvmInline
value class Speed private constructor(val mps: Double) {

    val kmh: Double get() = mps * KMH_PER_MPS

    /**
     * **Deliberately not [Comparable].** The operator alone gives `<`, `>`, `<=` and `>=` at every
     * call site and compiles to a static comparison of two doubles. Declaring the supertype would
     * also make the *generic* stdlib overloads applicable — `maxOf`, `coerceAtMost` and friends take
     * `Comparable`, so each argument is boxed and the result unboxed again, silently and with no
     * diagnostic. The fix-quality rules run this per fix on a recorder that is armed around the
     * clock, and that is how four allocations a fix appeared where there had been none. The members
     * below cover what those overloads were reached for; anything else is a compile error rather
     * than a quiet allocation.
     */
    operator fun compareTo(other: Speed): Int = mps.compareTo(other.mps)

    fun coerceAtMost(ceiling: Speed): Speed = if (mps > ceiling.mps) ceiling else this

    fun coerceAtLeast(floor: Speed): Speed = if (mps < floor.mps) floor else this

    operator fun times(factor: Double): Speed = Speed(mps * factor)

    companion object {
        /** Ground truth: what the platform reports and what geometry produces. */
        fun mps(value: Double): Speed = Speed(value)

        /** How a rule is written down. */
        fun kmh(value: Double): Speed = Speed(value / KMH_PER_MPS)

        val ZERO = Speed(0.0)

        /** A ceiling nothing clears — what a caller that declines to set one is really saying. */
        val UNLIMITED = Speed(Double.MAX_VALUE)

        private const val KMH_PER_MPS = 3.6
    }
}
