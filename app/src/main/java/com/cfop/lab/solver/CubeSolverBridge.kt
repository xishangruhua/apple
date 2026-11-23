package com.cfop.lab.solver

data class SolveOutcome(val ok: Boolean, val moves: String = "", val error: String = "")

object CubeSolverBridge {

    private val FACES = charArrayOf('U', 'R', 'F', 'D', 'L', 'B')

    fun simulateScrambleToFacelets(moves: List<String>): String {
        val s = CharArray(54) { idx -> FACES[idx / 9] }
        for (tok in moves) {
            val clean = tok.trim()
            if (clean.isEmpty()) continue
            val base = clean[0]
            val mod = clean.substring(1)
            val times = when (mod) {
                "'" -> 3
                "2" -> 2
                else -> 1
            }
            repeat(times) { applyBaseMove(s, base) }
        }
        return String(s)
    }

    private fun rotFaceCW(s: CharArray, off: Int) {
        val t = s.copyOfRange(off, off + 9)
        val map = intArrayOf(6, 3, 0, 7, 4, 1, 8, 5, 2)
        for (i in 0 until 9) s[off + i] = t[map[i]]
    }

    private fun cycle4(s: CharArray, a: Int, b: Int, c: Int, d: Int) {
        val tmp = s[d]
        s[d] = s[c]; s[c] = s[b]; s[b] = s[a]; s[a] = tmp
    }

    private fun applyBaseMove(s: CharArray, m: Char) {
        when (m) {
            'U' -> {
                rotFaceCW(s, 0)
                for (i in 0..2) cycle4(s, 18 + i, 36 + i, 45 + i, 9 + i)
            }
            'D' -> {
                rotFaceCW(s, 27)
                for (i in 6..8) cycle4(s, 18 + i, 9 + i, 45 + i, 36 + i)
            }
            'R' -> {
                rotFaceCW(s, 9)
                cycle4(s, 20, 2, 51, 29)
                cycle4(s, 23, 5, 48, 32)
                cycle4(s, 26, 8, 45, 35)
            }
            'L' -> {
                rotFaceCW(s, 36)
                cycle4(s, 0, 18, 27, 53)
                cycle4(s, 3, 21, 30, 50)
                cycle4(s, 6, 24, 33, 47)
            }
            'F' -> {
                rotFaceCW(s, 18)
                cycle4(s, 6, 9, 29, 44)
                cycle4(s, 7, 12, 28, 41)
                cycle4(s, 8, 15, 27, 38)
            }
            'B' -> {
                rotFaceCW(s, 45)
                cycle4(s, 2, 36, 33, 17)
                cycle4(s, 1, 39, 34, 14)
                cycle4(s, 0, 42, 35, 11)
            }
        }
    }

    fun solve(facelets54: String): SolveOutcome {
        if (facelets54.length != 54) return SolveOutcome(false, error = "状态串长度必须为 54")
        return try {
            val cls = Class.forName("cs.min2phase.Search")
            val instance = cls.getDeclaredConstructor().newInstance()
            val method = cls.getMethod(
                "solution",
                String::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val raw = method.invoke(instance, facelets54, 21, 100_000_000L, 0L, 0) as String
            if (raw.startsWith("Error")) {
                SolveOutcome(false, error = "魔方状态校验未通过（$raw），请点击下方 9 格微调有反光误判的贴纸。")
            } else {
                val cleaned = raw.trim().split(' ').filter { it.isNotBlank() }.joinToString(" ")
                SolveOutcome(true, moves = cleaned)
            }
        } catch (e: ClassNotFoundException) {
            SolveOutcome(false, error = "缺少 min2phase 核心类，请确认已拉取 cs0x7f/min2phase。")
        } catch (e: Exception) {
            SolveOutcome(false, error = "求解异常：${e.message}")
        }
    }
}
