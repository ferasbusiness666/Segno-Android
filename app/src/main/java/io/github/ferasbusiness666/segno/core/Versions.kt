package io.github.ferasbusiness666.segno.core

/** Compares versions like "1.0.0", "v1.2" and "1.3.0-beta1". */
object Versions {
    /** The numbers of a version: "v1.2.3-beta" → [1, 2, 3]. Null when it doesn't start with one. */
    fun parts(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val nums = core.split('.').map { it.toIntOrNull() ?: return null }
        return nums
    }

    /**
     * The versionCode for a version name: 1.2.3 → 1002003, so the code always goes up with the
     * name. app/build.gradle.kts uses the same rule. Null for names it can't read.
     */
    fun code(name: String): Long? {
        val p = parts(name) ?: return null
        if (p.size > 3 || p.any { it !in 0..999 }) return null
        val (major, minor, patch) = (p + listOf(0, 0, 0)).take(3)
        if (major > 2099) return null
        return major * 1_000_000L + minor * 1_000L + patch
    }

    private fun isPre(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('+').contains('-')

    /** True when [candidate] is a newer version than [current]. Pre-releases never count. */
    fun isNewer(candidate: String, current: String): Boolean {
        if (isPre(candidate)) return false
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
