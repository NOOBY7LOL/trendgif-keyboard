package com.trendgif.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 16f): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setPadding(0, dp(14), 0, dp(4))
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("trendgif", MODE_PRIVATE)
        var prov = prefs.getString("provider", "giphy") ?: "giphy"

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(40), dp(20), dp(20))

        val title = TextView(this)
        title.text = "TrendGIF Keyboard"
        title.textSize = 26f
        col.addView(title)

        col.addView(label(
            "Trending GIFs sent as WhatsApp-style GIFs.\n" +
                "Drag the little bar at the top of the keyboard to make it taller or shorter.", 14f
        ))

        // ---- source + key ----
        col.addView(label("1. GIF source and key"))
        val group = RadioGroup(this)
        val rg = RadioButton(this)
        rg.id = 1
        rg.text = "Giphy (recommended: lets me hide old GIFs)"
        val rk = RadioButton(this)
        rk.id = 2
        rk.text = "Klipy (no upload dates, so can't filter by age)"
        group.addView(rg)
        group.addView(rk)
        col.addView(group)

        val keyBox = EditText(this)
        keyBox.setSingleLine(true)
        col.addView(keyBox)

        fun keyName() = if (prov == "giphy") "giphy_key" else "klipy_key"
        fun refresh() {
            keyBox.hint = if (prov == "giphy") "Giphy API key (developers.giphy.com)" else "Klipy API key (partner.klipy.com)"
            keyBox.setText(prefs.getString(keyName(), ""))
        }
        group.check(if (prov == "giphy") 1 else 2)
        refresh()
        group.setOnCheckedChangeListener { _, id ->
            prov = if (id == 2) "klipy" else "giphy"
            prefs.edit().putString("provider", prov).apply()
            refresh()
        }

        val save = Button(this)
        save.text = "Save key"
        save.setOnClickListener {
            prefs.edit()
                .putString("provider", prov)
                .putString(keyName(), keyBox.text.toString().trim())
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        col.addView(save)

        // ---- recency ----
        col.addView(label("2. Only show GIFs newer than (Giphy)"))
        val ages = listOf(30 to "Last 30 days", 60 to "Last 2 months", 180 to "Last 6 months", 0 to "Any age")
        val ageGroup = RadioGroup(this)
        ages.forEachIndexed { i, pair ->
            val rb = RadioButton(this)
            rb.id = 100 + i
            rb.text = pair.second
            ageGroup.addView(rb)
        }
        val cur = prefs.getInt("max_age_days", 60)
        ageGroup.check(100 + ages.indexOfFirst { it.first == cur }.coerceAtLeast(1))
        ageGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt("max_age_days", ages[id - 100].first).apply()
        }
        col.addView(ageGroup)

        // ---- enable ----
        col.addView(label("3. Turn it on"))
        val enable = Button(this)
        enable.text = "Enable keyboard"
        enable.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        col.addView(enable)

        val pick = Button(this)
        pick.text = "Switch to TrendGIF"
        pick.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        col.addView(pick)

        col.addView(label(
            "Word suggestions use your phone's spell checker. If none show up, turn on " +
                "Settings > General management > Keyboard list and default > Spell check.", 13f
        ))

        val scroll = ScrollView(this)
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }
}
