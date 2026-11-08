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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("trendgif", MODE_PRIVATE)
        var prov = prefs.getString("provider", "klipy") ?: "klipy"

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(40), dp(20), dp(20))

        val title = TextView(this)
        title.text = "TrendGIF Keyboard"
        title.textSize = 26f
        col.addView(title)

        val info = TextView(this)
        info.text = "Shows what's trending right now and sends it as a WhatsApp-style GIF.\n\n" +
            "Step 1: Pick a GIF source and paste its free API key.\n" +
            "  Klipy: partner.klipy.com (made by the ex-Tenor team)\n" +
            "  Giphy: developers.giphy.com (Create an App -> API)\n" +
            "Step 2: Enable the keyboard.\n" +
            "Step 3: Switch to it, open WhatsApp, tap the GIF key."
        info.setPadding(0, dp(12), 0, dp(12))
        col.addView(info)

        val group = RadioGroup(this)
        val rk = RadioButton(this)
        rk.id = 1
        rk.text = "Klipy (closest to Tenor, recommended)"
        val rg = RadioButton(this)
        rg.id = 2
        rg.text = "Giphy"
        group.addView(rk)
        group.addView(rg)
        col.addView(group)

        val keyBox = EditText(this)
        keyBox.setSingleLine(true)
        col.addView(keyBox)

        fun keyName() = if (prov == "giphy") "giphy_key" else "klipy_key"
        fun refresh() {
            keyBox.hint = if (prov == "giphy") "Giphy API key" else "Klipy API key"
            keyBox.setText(prefs.getString(keyName(), ""))
        }
        group.check(if (prov == "giphy") 2 else 1)
        refresh()
        group.setOnCheckedChangeListener { _, id ->
            prov = if (id == 2) "giphy" else "klipy"
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

        val scroll = ScrollView(this)
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }
}
