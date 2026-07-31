package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.DistanceFn
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS84 ellipsoidal distance on a plain JVM — the same Vincenty inverse `Location.distanceBetween`
 * runs on the phone, ported so a replay off the device judges a fix by the metre the recorder did.
 * A sphere formula's error (a few tenths of a percent) is far below anything the rules test for
 * except at a jump ceiling's edge, which is exactly where a replay compares.
 *
 * The web viewer carries this same port for the same reason (`web/js/geo.js`); a rule that moves in
 * one moves in all three.
 */
internal val wgs84Distance = DistanceFn { latA, lonA, latB, lonB ->
    val a = 6378137.0
    val b = 6356752.3142
    val f = (a - b) / a
    val aSqMinusBSqOverBSq = (a * a - b * b) / (b * b)

    val lat1 = Math.toRadians(latA)
    val lat2 = Math.toRadians(latB)
    val bigL = Math.toRadians(lonB - lonA)

    val u1 = atan((1 - f) * tan(lat1))
    val u2 = atan((1 - f) * tan(lat2))
    val cosU1 = cos(u1)
    val cosU2 = cos(u2)
    val sinU1 = sin(u1)
    val sinU2 = sin(u2)
    val cosU1cosU2 = cosU1 * cosU2
    val sinU1sinU2 = sinU1 * sinU2

    var sigma = 0.0
    var deltaSigma = 0.0
    var bigA = 0.0
    var lambda = bigL
    var iterations = 0
    while (iterations++ < MAX_ITERS) {
        val lambdaOrig = lambda
        val cosLambda = cos(lambda)
        val sinLambda = sin(lambda)
        val t1 = cosU2 * sinLambda
        val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        val sinSqSigma = t1 * t1 + t2 * t2
        val sinSigma = sqrt(sinSqSigma)
        val cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda
        sigma = Math.atan2(sinSigma, cosSigma)
        val sinAlpha = if (sinSigma == 0.0) 0.0 else cosU1cosU2 * sinLambda / sinSigma
        val cosSqAlpha = 1 - sinAlpha * sinAlpha
        val cos2SM = if (cosSqAlpha == 0.0) 0.0 else cosSigma - 2.0 * sinU1sinU2 / cosSqAlpha
        val uSquared = cosSqAlpha * aSqMinusBSqOverBSq

        bigA = 1 + (uSquared / 16384.0) *
            (4096.0 + uSquared * (-768 + uSquared * (320.0 - 175.0 * uSquared)))
        val bigB = (uSquared / 1024.0) *
            (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)))
        val bigC = (f / 16.0) * cosSqAlpha * (4.0 + f * (4.0 - 3.0 * cosSqAlpha))
        val cos2SMSq = cos2SM * cos2SM
        deltaSigma = bigB * sinSigma *
            (
                cos2SM + (bigB / 4.0) *
                    (
                        cosSigma * (-1.0 + 2.0 * cos2SMSq) -
                            (bigB / 6.0) * cos2SM * (-3.0 + 4.0 * sinSigma * sinSigma) *
                            (-3.0 + 4.0 * cos2SMSq)
                        )
                )

        lambda = bigL +
            (1.0 - bigC) * f * sinAlpha *
            (sigma + bigC * sinSigma * (cos2SM + bigC * cosSigma * (-1.0 + 2.0 * cos2SM * cos2SM)))

        val delta = (lambda - lambdaOrig) / lambda
        if (Math.abs(delta) < 1.0e-12) break
    }
    (b * bigA * (sigma - deltaSigma)).toFloat().toDouble()
}

private const val MAX_ITERS = 20
