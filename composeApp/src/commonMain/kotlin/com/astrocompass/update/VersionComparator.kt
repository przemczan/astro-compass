package com.astrocompass.update

/** True if [candidate] ranks after [current] -- see [compareVersions]. */
fun isNewerVersion(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

/**
 * Compares two `vMAJOR.MINOR.PATCH[-suffix]` tags (the leading `v` optional) purely numerically on
 * MAJOR.MINOR.PATCH; a suffixed version (a pre-release, e.g. `-beta`) ranks below the same numeric
 * version with none, and two suffixed versions tie-break alphabetically. Not full semver -- this
 * app's own tags (`v2.3.0-beta`, `v1.2.0`, ...) never need more than that.
 */
internal fun compareVersions(a: String, b: String): Int {
    val (aNumbers, aSuffix) = parseVersion(a)
    val (bNumbers, bSuffix) = parseVersion(b)
    for (index in 0 until maxOf(aNumbers.size, bNumbers.size)) {
        val comparison = aNumbers.getOrElse(index) { 0 }.compareTo(bNumbers.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return when {
        aSuffix == null && bSuffix == null -> 0
        aSuffix == null -> 1
        bSuffix == null -> -1
        else -> aSuffix.compareTo(bSuffix)
    }
}

private fun parseVersion(raw: String): Pair<List<Int>, String?> {
    val trimmed = raw.removePrefix("v")
    val dashIndex = trimmed.indexOf('-')
    val numericPart = if (dashIndex >= 0) trimmed.substring(0, dashIndex) else trimmed
    val suffix = if (dashIndex >= 0) trimmed.substring(dashIndex + 1) else null
    return numericPart.split(".").mapNotNull { it.toIntOrNull() } to suffix
}
