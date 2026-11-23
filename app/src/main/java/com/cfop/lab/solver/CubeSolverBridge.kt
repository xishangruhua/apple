package com.cfop.lab.solver

data class SolveOutcome(val ok: Boolean, val moves: String = "", val error: String = "")

object CubeSolverBridge {
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
                SolveOutcome(true, moves = raw.trim().replace(Regex("\\s+"), " "))
            }
        } catch (e: ClassNotFoundException) {
            SolveOutcome(false, error = "缺少 min2phase 核心类，请使用内置 GitHub Actions 云端编译或先运行 fetch-deps。")
        } catch (e: Exception) {
            SolveOutcome(false, error = "求解异常：${e.message}")
        }
    }
}
