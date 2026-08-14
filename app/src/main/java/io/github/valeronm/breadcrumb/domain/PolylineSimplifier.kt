package io.github.valeronm.breadcrumb.domain

/**
 * Line simplification for drawing many tracks at once: a radial-distance pre-pass then
 * Douglas–Peucker, over flat `[lon, lat, lon, lat, …]` arrays.
 *
 * The tolerance is in **degrees, deliberately** — the same plane the map projects, so error is
 * uniform on screen rather than on the ground. A degree of longitude shrinks with latitude, which
 * only makes the pass keep *more* east–west detail far from the equator; it never drops below the
 * stated fidelity. A case-for-case port of the web viewer's `simplify.js` — a change here moves
 * there too.
 */
object PolylineSimplifier {

    fun simplify(coords: DoubleArray, toleranceDeg: Double): DoubleArray {
        if (coords.size / 2 <= 2) return coords.copyOf()
        val sqTol = toleranceDeg * toleranceDeg
        return dpPass(radialPass(coords, sqTol), sqTol)
    }

    // A flat primitive buffer, not a List<Double>: this pass sees every fix of every track before
    // anything is reduced, and boxing two Doubles per kept point is the hot loop's whole cost.
    private fun radialPass(coords: DoubleArray, sqTol: Double): DoubleArray {
        val out = DoubleArray(coords.size + 2)
        out[0] = coords[0]
        out[1] = coords[1]
        var n = 2
        var px = coords[0]
        var py = coords[1]
        for (i in 2 until coords.size step 2) {
            val x = coords[i]
            val y = coords[i + 1]
            val dx = x - px
            val dy = y - py
            if (dx * dx + dy * dy > sqTol) {
                out[n++] = x
                out[n++] = y
                px = x
                py = y
            }
        }
        // Always keep the true endpoint.
        if (out[n - 2] != coords[coords.size - 2] || out[n - 1] != coords[coords.size - 1]) {
            out[n++] = coords[coords.size - 2]
            out[n++] = coords[coords.size - 1]
        }
        return out.copyOf(n)
    }

    private fun dpPass(coords: DoubleArray, sqTol: Double): DoubleArray {
        val n = coords.size / 2
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxSq = sqTol
            var index = -1
            val ax = coords[first * 2]
            val ay = coords[first * 2 + 1]
            val bx = coords[last * 2]
            val by = coords[last * 2 + 1]
            for (i in first + 1 until last) {
                val sq = sqSegDist(coords[i * 2], coords[i * 2 + 1], ax, ay, bx, by)
                if (sq > maxSq) {
                    index = i
                    maxSq = sq
                }
            }
            if (index != -1) {
                keep[index] = true
                stack.addLast(intArrayOf(first, index))
                stack.addLast(intArrayOf(index, last))
            }
        }
        val out = DoubleArray(keep.count { it } * 2)
        var j = 0
        for (i in 0 until n) {
            if (keep[i]) {
                out[j++] = coords[i * 2]
                out[j++] = coords[i * 2 + 1]
            }
        }
        return out
    }

    // Six scalars, deliberately: the port stays line-for-line against simplify.js, and a point
    // type here would put an allocation inside the O(n log n) hot loop.
    @Suppress("LongParameterList")
    private fun sqSegDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        var x = ax
        var y = ay
        var dx = bx - ax
        var dy = by - ay
        if (dx != 0.0 || dy != 0.0) {
            val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
            if (t > 1) {
                x = bx
                y = by
            } else if (t > 0) {
                x += dx * t
                y += dy * t
            }
        }
        dx = px - x
        dy = py - y
        return dx * dx + dy * dy
    }
}
