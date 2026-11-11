package com.granularvolume.util

import android.content.Context

/**
 * F-Droid flavor: the full quiet range is free and complete, permanently.
 *
 * This is a commitment, not a shortcut. The FOSS build is listed on directories
 * whose inclusion policy requires "free of charge" (android-foss), and the
 * project's public claims (no ads, no tracking, nothing withheld) were made to
 * that audience. The stub mirrors the play-flavor [KeyCheck] signature exactly,
 * the same pattern as the flavor-split ReviewHelper.
 */
object KeyCheck {

    @Suppress("UNUSED_PARAMETER")
    fun isKeyInstalled(context: Context): Boolean = true
}
