package com.trendgif.keyboard

import android.content.ClipDescription
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MIN_FILL = 18 // keep loading more pages until the grid has this many GIFs
private val BG = Color.parseColor("#1C1C1E")
private val KEY = Color.parseColor("#3A3A3C")
private val KEY_DARK = Color.parseColor("#2C2C2E")
private val ACCENT = Color.parseColor("#25D366")

class GifKeyboardService : InputMethodService() {

    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("trendgif", MODE_PRIVATE) }

    private lateinit var root: LinearLayout
    private lateinit var keysPanel: LinearLayout
    private lateinit var gifPanel: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: GifAdapter

    private var shift = false
    private var symbols = false

    // word suggestions
    private var spell: SpellCheckerSession? = null
    private var currentWord = ""

    // gif loading
    private var query: String? = null // null = trending
    private var offset = 0
    private var loading = false
    private var endReached = false
    private var generation = 0
    private var autoPages = 0
    private var loadedProvider = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val spellListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val out = LinkedHashSet<String>()
            results?.forEach { r ->
                for (i in 0 until r.suggestionsCount) {
                    val si = r.getSuggestionsInfoAt(i)
                    for (j in 0 until si.suggestionsCount) out.add(si.getSuggestionAt(j))
                }
            }
            ui.post { showSuggestions(out.take(3)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cleanCache()
        try {
            spell = getSystemService(TextServicesManager::class.java)
                ?.newSpellCheckerSession(null, Locale.getDefault(), spellListener, true)
        } catch (e: Exception) {
            spell = null
        }
    }

    override fun onDestroy() {
        try {
            spell?.close()
        } catch (e: Exception) {
        }
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- main view ----------

    private fun minH() = dp(230)
    private fun maxH() = minOf(dp(500), (resources.displayMetrics.heightPixels * 0.65).toInt())

    override fun onCreateInputView(): View {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.layoutParams = ViewGroup.LayoutParams(MATCH, dp(prefs.getInt("kb_height_dp", 330)).coerceIn(minH(), maxH()))

        root.addView(buildHandle(), LinearLayout.LayoutParams(MATCH, dp(28)))

        val body = FrameLayout(this)
        strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL

        keysPanel = LinearLayout(this)
        keysPanel.orientation = LinearLayout.VERTICAL
        keysPanel.setPadding(dp(4), 0, dp(4), dp(6))

        gifPanel = buildGifPanel()
        gifPanel.visibility = View.GONE

        body.addView(keysPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(gifPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        buildKeys()
        return root
    }

    // drag this bar up/down to stretch the keyboard
    private fun buildHandle(): View {
        val h = FrameLayout(this)
        val pill = View(this)
        val shape = GradientDrawable()
        shape.setColor(Color.GRAY)
        shape.cornerRadius = dp(2).toFloat()
        pill.background = shape
        h.addView(pill, FrameLayout.LayoutParams(dp(48), dp(4), Gravity.CENTER))

        var startY = 0f
        var startH = 0
        h.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    startH = root.layoutParams.height
                }
                MotionEvent.ACTION_MOVE -> {
                    val nh = (startH + (startY - e.rawY)).toInt().coerceIn(minH(), maxH())
                    root.layoutParams.height = nh
                    root.requestLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dpH = (root.layoutParams.height / resources.displayMetrics.density).toInt()
                    prefs.edit().putInt("kb_height_dp", dpH).apply()
                }
            }
            true
        }
        return h
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::keysPanel.isInitialized) {
            showSuggestions(emptyList())
            showKeys()
        }
    }

    private fun showKeys() {
        gifPanel.visibility = View.GONE
        keysPanel.visibility = View.VISIBLE
    }

    private fun showGifs() {
        keysPanel.visibility = View.GONE
        gifPanel.visibility = View.VISIBLE
        if (adapter.itemCount == 0 || loadedProvider != provider()) loadGifs(true)
    }

    // ---------- typing keyboard ----------

    private fun key(
        label: String,
        weight: Float = 1f,
        bg: Int = KEY,
        fg: Int = Color.WHITE,
        onClick: () -> Unit
    ): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(fg)
        t.textSize = 18f
        t.gravity = Gravity.CENTER
        val shape = GradientDrawable()
        shape.setColor(bg)
        shape.cornerRadius = dp(6).toFloat()
        t.background = shape
        val lp = LinearLayout.LayoutParams(0, MATCH, weight)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        t.layoutParams = lp
        t.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        return t
    }

    private fun row(views: List<View>, indentDp: Int): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(dp(indentDp), 0, dp(indentDp), 0)
        views.forEach { r.addView(it) }
        r.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        return r
    }

    private fun charKey(c: Char): TextView {
        val s = if (!symbols && shift) c.uppercaseChar().toString() else c.toString()
        return key(s) {
            type(s)
            if (shift && !symbols) {
                shift = false
                buildKeys()
            }
        }
    }

    private fun backspaceKey(): View {
        val t = key("⌫", 1.5f, KEY_DARK) {}
        val rep = object : Runnable {
            override fun run() {
                deleteOne()
                ui.postDelayed(this, 45)
            }
        }
        t.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteOne()
                    ui.postDelayed(rep, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(rep)
            }
            true
        }
        return t
    }

    private fun buildKeys() {
        keysPanel.removeAllViews()
        keysPanel.addView(strip, LinearLayout.LayoutParams(MATCH, dp(38)))

        val rows = if (symbols) {
            listOf("1234567890", "@#$%&-+()/", "*\"':;!?")
        } else {
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        }
        keysPanel.addView(row(rows[0].map { charKey(it) }, 0))
        keysPanel.addView(row(rows[1].map { charKey(it) }, if (symbols) 0 else 16))

        val third = ArrayList<View>()
        third.add(key(if (symbols) "" else "⇧", 1.5f, if (shift) ACCENT else KEY_DARK) {
            if (!symbols) {
                shift = !shift
                buildKeys()
            }
        })
        rows[2].forEach { third.add(charKey(it)) }
        third.add(backspaceKey())
        keysPanel.addView(row(third, 0))

        val bottom = ArrayList<View>()
        bottom.add(key(if (symbols) "ABC" else "?123", 1.5f, KEY_DARK) {
            symbols = !symbols
            buildKeys()
        })
        bottom.add(key("GIF", 1.5f, ACCENT, Color.BLACK) { showGifs() })
        bottom.add(key(",") { type(",") })
        bottom.add(key("space", 4f) { type(" ") })
        bottom.add(key(".") { type(".") })
        bottom.add(key("⏎", 1.5f, KEY_DARK) { enter() })
        keysPanel.addView(row(bottom, 0))
    }

    private fun type(s: String) {
        currentInputConnection?.commitText(s, 1)
        updateSuggestions()
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
        updateSuggestions()
    }

    private fun enter() {
        if (!sendDefaultEditorAction(true)) currentInputConnection?.commitText("\n", 1)
        showSuggestions(emptyList())
    }

    // ---------- word suggestions ----------

    private fun updateSuggestions() {
        val ic = currentInputConnection
        if (ic == null || spell == null) {
            showSuggestions(emptyList())
            return
        }
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        currentWord = word
        if (word.length < 2) {
            showSuggestions(emptyList())
            return
        }
        try {
            spell?.getSentenceSuggestions(arrayOf(TextInfo(word)), 3)
        } catch (e: Exception) {
            showSuggestions(emptyList())
        }
    }

    private fun showSuggestions(list: List<String>) {
        if (!::strip.isInitialized) return
        strip.removeAllViews()
        list.forEach { s ->
            val t = TextView(this)
            t.text = s
            t.setTextColor(Color.WHITE)
            t.textSize = 16f
            t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            t.setOnClickListener { applySuggestion(s) }
            strip.addView(t)
        }
    }

    private fun applySuggestion(s: String) {
        val ic = currentInputConnection ?: return
        if (currentWord.isNotEmpty()) ic.deleteSurroundingText(currentWord.length, 0)
        ic.commitText("$s ", 1)
        currentWord = ""
        showSuggestions(emptyList())
    }

    // ---------- GIF panel ----------

    private fun chip(label: String, accent: Boolean = false, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(if (accent) Color.BLACK else Color.WHITE)
        t.textSize = 14f
        t.setPadding(dp(14), dp(7), dp(14), dp(7))
        val shape = GradientDrawable()
        shape.setColor(if (accent) ACCENT else KEY)
        shape.cornerRadius = dp(16).toFloat()
        t.background = shape
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        t.layoutParams = lp
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildGifPanel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(chip("ABC", true) { showKeys() })

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.addView(chip("🔥 Trending") { setQuery(null) })
        listOf(
            "Reaction", "Funny", "Meme", "Love", "Dance", "Sad", "Wow",
            "Thank you", "Good morning", "Angry", "Hello", "Laugh"
        ).forEach { q -> chips.addView(chip(q) { setQuery(q) }) }
        scroll.addView(chips)
        top.addView(scroll, LinearLayout.LayoutParams(0, WRAP, 1f))
        p.addView(top, LinearLayout.LayoutParams(MATCH, dp(46)))

        val frame = FrameLayout(this)
        val rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, 3)
        adapter = GifAdapter()
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !r.canScrollVertically(1)) {
                    autoPages = 0
                    loadGifs(false)
                }
            }
        })
        frame.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))

        status = TextView(this)
        status.setTextColor(Color.LTGRAY)
        status.gravity = Gravity.CENTER
        status.setPadding(dp(24), 0, dp(24), 0)
        status.visibility = View.GONE
        frame.addView(status, FrameLayout.LayoutParams(MATCH, MATCH))

        p.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return p
    }

    private fun setStatus(msg: String?) {
        status.text = msg.orEmpty()
        status.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun setQuery(q: String?) {
        query = q
        loadGifs(true)
    }

    private fun provider(): String = prefs.getString("provider", "giphy") ?: "giphy"

    private fun apiKey(prov: String): String =
        prefs.getString(if (prov == "giphy") "giphy_key" else "klipy_key", "").orEmpty().trim()

    private fun fetchPage(prov: String, key: String, q: String?, off: Int): GifPage =
        if (prov == "giphy") {
            val age = prefs.getInt("max_age_days", 60)
            if (q == null) GiphyClient.trending(key, off, age) else GiphyClient.search(key, q, off, age)
        } else {
            if (q == null) KlipyClient.trending(key, off) else KlipyClient.search(key, q, off)
        }

    private fun loadGifs(reset: Boolean) {
        if (reset) {
            loadedProvider = provider()
            generation++
            offset = 0
            endReached = false
            loading = false
            autoPages = 0
            adapter.clear()
        }
        if (loading || endReached) return
        val prov = loadedProvider.ifBlank { provider() }
        val key = apiKey(prov)
        if (key.isBlank()) {
            setStatus("Open the TrendGIF app and paste your free API key")
            return
        }
        loading = true
        if (adapter.itemCount == 0) setStatus("Loading…")
        val gen = generation
        val q = query
        val off = offset
        io.execute {
            val page = try {
                fetchPage(prov, key, q, off)
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (gen != generation) return@post
                loading = false
                if (page == null) {
                    setStatus("Couldn't load GIFs. Check internet and API key")
                    return@post
                }
                if (page.rawCount == 0) {
                    endReached = true
                    setStatus(if (adapter.itemCount == 0) "No GIFs found" else null)
                    return@post
                }
                offset += page.rawCount
                adapter.add(page.items)
                if (adapter.itemCount >= MIN_FILL || autoPages >= 3) {
                    setStatus(
                        if (adapter.itemCount == 0) "No recent GIFs here. Try Trending, or allow older GIFs in the app"
                        else null
                    )
                } else {
                    // the "recent only" filter removed most of this page, so fetch the next one
                    autoPages++
                    loadGifs(false)
                }
            }
        }
    }

    // ---------- sending ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun sendGif(item: GifItem) {
        val info = currentInputEditorInfo ?: return
        val mimes = EditorInfoCompat.getContentMimeTypes(info).toList()
        fun accepts(m: String) = mimes.any { ClipDescription.compareMimeTypes(m, it) }

        // WhatsApp-style GIFs are looping MP4 videos. Use MP4 when the app accepts it,
        // otherwise fall back to a normal GIF file.
        val wantMp4 = item.mp4Url != null && accepts("video/mp4")
        val wantGif = accepts("image/gif")
        if (!wantMp4 && !wantGif) {
            toast("This app doesn't accept GIFs from keyboards")
            return
        }
        val url = if (wantMp4) item.mp4Url!! else item.gifUrl
        val mime = if (wantMp4) "video/mp4" else "image/gif"
        val ext = if (wantMp4) "mp4" else "gif"

        io.execute {
            try {
                val dir = File(cacheDir, "gifs")
                dir.mkdirs()
                val f = File(dir, "${item.id}.$ext")
                if (!f.exists() || f.length() == 0L) GiphyClient.download(url, f)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                ui.post {
                    val ic = currentInputConnection
                    val ei = currentInputEditorInfo
                    if (ic == null || ei == null) return@post
                    val content = InputContentInfoCompat(uri, ClipDescription(item.id, arrayOf(mime)), null)
                    val ok = InputConnectionCompat.commitContent(
                        ic, ei, content,
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
                    )
                    if (!ok) toast("The app rejected this GIF")
                }
            } catch (e: Exception) {
                ui.post { toast("Couldn't download the GIF") }
            }
        }
    }

    private fun cleanCache() {
        File(cacheDir, "gifs").listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > 24 * 3600 * 1000L) it.delete()
        }
    }

    // ---------- adapter ----------

    private inner class GifAdapter : RecyclerView.Adapter<GifAdapter.VH>() {
        private val items = ArrayList<GifItem>()

        inner class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        fun clear() {
            val n = items.size
            items.clear()
            notifyItemRangeRemoved(0, n)
        }

        fun add(l: List<GifItem>) {
            val s = items.size
            items.addAll(l)
            notifyItemRangeInserted(s, l.size)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context)
            iv.layoutParams = RecyclerView.LayoutParams(MATCH, dp(110))
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setPadding(dp(1), dp(1), dp(1), dp(1))
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            Glide.with(applicationContext)
                .load(item.previewUrl)
                .placeholder(ColorDrawable(KEY))
                .into(holder.iv)
            holder.iv.setOnClickListener { sendGif(item) }
        }

        override fun onViewRecycled(holder: VH) {
            Glide.with(applicationContext).clear(holder.iv)
        }
    }
}
