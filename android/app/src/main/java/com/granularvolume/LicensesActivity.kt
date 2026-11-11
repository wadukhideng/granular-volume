package com.granularvolume

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.granularvolume.util.LicenseInfo

/**
 * Open-source notices.
 *
 * Why this screen exists: the app bundles third-party libraries under the Apache License 2.0, and
 * that licence requires recipients to be given the attribution notices and a copy of the licence.
 * Until this screen existed the app satisfied neither. It is the one place in a competitor
 * comparison where we were behind on an actual obligation rather than on drafting.
 *
 * Built with no dependency and no layout file, and the component list is flavor-split, so the
 * F-Droid build cannot accidentally claim to contain a proprietary library and the Play build
 * cannot accidentally hide one.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.gv_licenses_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.gv_background))
            val p = dp(20)
            setPadding(p, dp(24), p, dp(40))
        }

        root.addView(head(getString(R.string.gv_licenses_title)))
        root.addView(body(getString(R.string.gv_licenses_intro)))

        root.addView(section(getString(R.string.gv_licenses_oss)))
        for ((name, licence) in LicenseInfo.openSource) {
            root.addView(entry(name, licence))
        }

        if (LicenseInfo.proprietary.isNotEmpty()) {
            root.addView(section(getString(R.string.gv_licenses_proprietary)))
            for ((name, licence) in LicenseInfo.proprietary) {
                root.addView(entry(name, licence))
            }
        }

        root.addView(section(getString(R.string.gv_licenses_own)))
        root.addView(body(getString(R.string.gv_licenses_own_body)))

        root.addView(section(getString(R.string.gv_licenses_apache)))
        root.addView(mono(readApache()))

        val scroller = ScrollView(this).apply {
            setBackgroundColor(color(R.color.gv_background))
            addView(root)
            clipToPadding = false
        }
        // targetSdk 36 enforces edge to edge, so the window draws under the status and
        // navigation bars. Without this the heading sits behind the clock.
        ViewCompat.setOnApplyWindowInsetsListener(scroller) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        setContentView(scroller)
    }

    private fun readApache(): String = try {
        resources.openRawResource(R.raw.license_apache_2_0)
            .bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        // The notice must never be the thing that crashes the app.
        "Apache License 2.0. Full text: https://www.apache.org/licenses/LICENSE-2.0"
    }

    // ── view helpers ────────────────────────────────────────────────────────

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun head(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.gv_text_primary))
        space(bottom = 10)
    }

    private fun section(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.gv_accent_text))
        space(top = 26, bottom = 8)
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(color(R.color.gv_text_secondary))
        space(bottom = 4)
    }

    private fun entry(name: String, licence: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.gv_text_primary))
        })
        addView(TextView(context).apply {
            text = licence
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(R.color.gv_text_muted))
        })
        space(bottom = 12)
    }

    private fun mono(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(color(R.color.gv_text_muted))
        typeface = Typeface.MONOSPACE
        space(top = 4)
    }

    private fun View.space(top: Int = 0, bottom: Int = 0) {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
