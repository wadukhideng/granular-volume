package com.granularvolume.util

import android.content.Context
import android.content.pm.PackageManager

object PermissionHelper {
    /** MODIFY_AUDIO_SETTINGS adalah normal permission, otomatis granted. */
    fun hasModifyAudioSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
}
