package com.cfop.lab.vision

import kotlin.math.*

object RubiksColorResolver {

    val FACES = charArrayOf('U', 'R', 'F', 'D', 'L', 'B')
    private val CENTER_INDEX = intArrayOf(4, 13, 22, 31, 40, 49)

    private val PRIOR_LAB = mapOf(
        'D' to doubleArrayOf(90.0, 0.0, 2.0),
        'U' to doubleArrayOf(84.0, -4.0, 76.0),
        'F' to doubleArrayOf(54.0, -50.0, 32.0),
        'B' to doubleArrayOf(34.0, 16.0, -54.0),
        'R' to doubleArrayOf(44.0, 62.0, 32.0),
        'L' to doubleArrayOf(62.0, 40.0, 60.0)
    )

    fun previewSingleFace(rgb9: List<IntArray>, centerFace: Char): CharArray {
        val out = CharArray(9)
        for (i in 0 until 9) {
            if (i == 4) {
                out[i] = centerFace
            } else {
                val lab = rgbToLab(rgb9[i][0], rgb9[i][1], rgb9[i][2])
                out[i] = FACES.minByOrNull { f -> ciede2000(lab, PRIOR_LAB[f]!!) } ?: 'D'
            }
        }
        return out
    }

    fun resolve54Facelets(rgb54: List<IntArray>): CharArray {
        require(rgb54.size == 54) { "需要完整的 6 面 54 格 RGB 样本" }
        val labs = rgb54.map { rgbToLab(it[0], it[1], it[2]) }
        val centerLabs = FACES.indices.associate { i -> FACES[i] to labs[CENTER_INDEX[i]] }

        val out = CharArray(54)
        for (i in FACES.indices) out[CENTER_INDEX[i]] = FACES[i]

        data class Candidate(val idx: Int, val ranked: List<Pair<Char, Double>>, val margin: Double)
        val nonCenters = mutableListOf<Candidate>()
        for (idx in 0 until 54) {
            if (idx % 9 == 4) continue
            val ranked = FACES.map { f -> f to ciede2000(labs[idx], centerLabs[f]!!) }.sortedBy { it.second }
            nonCenters.add(Candidate(idx, ranked, ranked[1].second - ranked[0].second))
        }

        nonCenters.sortByDescending { it.margin }
        val remaining = FACES.associateWith { 8 }.toMutableMap()
        for (cand in nonCenters) {
            val pick = cand.ranked.firstOrNull { (remaining[it.first] ?: 0) > 0 }?.first
                ?: cand.ranked.first().first
            out[cand.idx] = pick
            remaining[pick] = (remaining[pick] ?: 1) - 1
        }
        return out
    }

    private fun rgbToLab(r: Int, g: Int, b: Int): DoubleArray {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4) else v / 12.92
        }
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        val x = (rl * 0.4124 + gl * 0.3576 + bl * 0.1805) / 0.95047
        val y = (rl * 0.2126 + gl * 0.7152 + bl * 0.0722) / 1.00000
        val z = (rl * 0.0193 + gl * 0.1192 + bl * 0.9505) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else (7.787 * t) + 16.0 / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun ciede2000(lab1: DoubleArray, lab2: DoubleArray): Double {
        val l1 = lab1[0]; val a1 = lab1[1]; val b1 = lab1[2]
        val l2 = lab2[0]; val a2 = lab2[1]; val b2 = lab2[2]
        val c1 = hypot(a1, b1); val c2 = hypot(a2, b2)
        val avgC = (c1 + c2) / 2.0
        val g = 0.5 * (1.0 - sqrt(avgC.pow(7.0) / (avgC.pow(7.0) + 25.0.pow(7.0))))
        val a1p = (1.0 + g) * a1; val a2p = (1.0 + g) * a2
        val c1p = hypot(a1p, b1); val c2p = hypot(a2p, b2)
        fun hp(b: Double, ap: Double): Double {
            if (b == 0.0 && ap == 0.0) return 0.0
            val deg = Math.toDegrees(atan2(b, ap))
            return if (deg >= 0) deg else deg + 360.0
        }
        val h1p = hp(b1, a1p); val h2p = hp(b2, a2p)
        val dLp = l2 - l1; val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p - h1p > 180.0 -> h2p - h1p - 360.0
            else -> h2p - h1p + 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))
        val avgLp = (l1 + l2) / 2.0; val avgCp = (c1p + c2p) / 2.0
        val avgHp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }
        val t = 1.0 - 0.17 * cos(Math.toRadians(avgHp - 30.0)) +
                0.24 * cos(Math.toRadians(2.0 * avgHp)) +
                0.32 * cos(Math.toRadians(3.0 * avgHp + 6.0)) -
                0.20 * cos(Math.toRadians(4.0 * avgHp - 63.0))
        val sL = 1.0 + (0.015 * (avgLp - 50.0).pow(2.0)) / sqrt(20.0 + (avgLp - 50.0).pow(2.0))
        val sC = 1.0 + 0.045 * avgCp
        val sH = 1.0 + 0.015 * avgCp * t
        val dTheta = 30.0 * exp(-((avgHp - 275.0) / 25.0).pow(2.0))
        val rC = 2.0 * sqrt(avgCp.pow(7.0) / (avgCp.pow(7.0) + 25.0.pow(7.0)))
        val rT = -sin(Math.toRadians(2.0 * dTheta)) * rC
        return sqrt(
            (dLp / sL).pow(2.0) + (dCp / sC).pow(2.0) + (dHp / sH).pow(2.0) +
            rT * (dCp / sC) * (dHp / sH)
        )
    }
}
