package com.granularvolume.util

/**
 * F-Droid build: every bundled component is open source, which is the point of this build.
 *
 * Flavor-split for the same reason as [ReviewHelper] and [KeyCheck]: the Play build carries one
 * proprietary Google library that this build does not, and a licences screen that listed it here
 * would be false.
 */
object LicenseInfo {

    /** name to licence, shown in order. */
    val openSource: List<Pair<String, String>> = listOf(
        "AndroidX Core KTX" to "The Android Open Source Project, Apache License 2.0",
        "AndroidX AppCompat" to "The Android Open Source Project, Apache License 2.0",
        "AndroidX Lifecycle Service" to "The Android Open Source Project, Apache License 2.0",
        "Material Components for Android" to "Google LLC, Apache License 2.0",
        "Kotlin Coroutines for Android" to "JetBrains s.r.o., Apache License 2.0",
        "Kotlin Standard Library" to "JetBrains s.r.o. and Kotlin Programming Language contributors, Apache License 2.0",
    )

    /** Proprietary components. Empty here, and that is the whole difference between the builds. */
    val proprietary: List<Pair<String, String>> = emptyList()
}
