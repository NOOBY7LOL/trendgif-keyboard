package com.trendgif.keyboard

import android.content.ClipDescription
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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

// what a key does when pressed (and optionally when released)
private class KeyAction(val onPress: () -> Unit, val onRelease: (() -> Unit)? = null)

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

    // GIF search mode: typed letters go into searchBuf instead of the chat
    private var searchMode = false
    private val searchBuf = StringBuilder()

    // word suggestions (word is tracked locally so typing never waits on the app)
    private var spell: SpellCheckerSession? = null
    private var currentWord = ""
    private var lastQueried = ""
    private val suggestRun = Runnable { runSpell() }

    // gif loading
    private var query: String? = null // null = trending
    private var offset = 0
    private var loading = false
    private var endReached = false
    private var generation = 0
    private var autoPages = 0
    private var loadedProvider = ""
    private var loadedSig = ""
    private var loadedAt = 0L

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
            ui.post { if (lastQueried == currentWord) showSuggestions(out.take(3)) }
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

    // Takes over ALL touches on the keys so every finger is handled by itself.
    // This is what makes fast two-thumb typing reliable.
    private inner class KeyArea(ctx: android.content.Context) : LinearLayout(ctx) {
        private val held = HashMap<Int, View>()

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

        private fun hit(vg: ViewGroup, x: Float, y: Float): View? {
            for (i in vg.childCount - 1 downTo 0) {
                val c = vg.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                if (x < c.left || x >= c.right || y < c.top || y >= c.bottom) continue
                if (c.tag is KeyAction) return c
                if (c is ViewGroup) {
                    val r = hit(c, x - c.left, y - c.top)
                    if (r != null) return r
                }
            }
            return null
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    val v = hit(this, e.getX(i), e.getY(i))
                    val a = v?.tag as? KeyAction
                    if (v != null && a != null) {
                        held[e.getPointerId(i)] = v
                        v.alpha = 0.6f
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        a.onPress()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val v = held.remove(e.getPointerId(e.actionIndex))
                    if (v != null) {
                        v.alpha = 1f
                        (v.tag as? KeyAction)?.onRelease?.invoke()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    held.values.forEach {
                        it.alpha = 1f
                        (it.tag as? KeyAction)?.onRelease?.invoke()
                    }
                    held.clear()
                }
            }
            return true
        }
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.layoutParams = ViewGroup.LayoutParams(MATCH, dp(prefs.getInt("kb_height_dp", 330)).coerceIn(minH(), maxH()))

        root.addView(buildHandle(), LinearLayout.LayoutParams(MATCH, dp(28)))

        val body = FrameLayout(this)
        strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL

        keysPanel = KeyArea(this)
        keysPanel.orientation = LinearLayout.VERTICAL
        keysPanel.setPadding(0, 0, 0, dp(4))

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
            searchMode = false
            searchBuf.setLength(0)
            currentWord = ""
            buildKeys()
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
        val stale = System.currentTimeMillis() - loadedAt > 10 * 60 * 1000L
        if (adapter.itemCount == 0 || loadedSig != settingsSig() || stale) loadGifs(true)
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
        // the gap between keys is drawn INSIDE the key, so there are no dead zones
        t.background = InsetDrawable(shape, dp(2), dp(3), dp(2), dp(3))
        t.layoutParams = LinearLayout.LayoutParams(0, MATCH, weight)
        t.tag = KeyAction(onClick)
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
        t.tag = KeyAction(
            { deleteOne(); ui.postDelayed(rep, 400) },
            { ui.removeCallbacks(rep) }
        )
        return t
    }

    private fun buildKeys() {
        keysPanel.removeAllViews()
        (strip.parent as? ViewGroup)?.removeView(strip)
        keysPanel.addView(strip, LinearLayout.LayoutParams(MATCH, dp(38)))
        if (searchMode) renderSearchBar()

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
        bottom.add(key(if (searchMode) "✕" else "GIF", 1.5f, ACCENT, Color.BLACK) {
            if (searchMode) cancelSearch() else showGifs()
        })
        bottom.add(key(",") { type(",") })
        bottom.add(key("space", 4f) { type(" ") })
        bottom.add(key(".") { type(".") })
        bottom.add(
            key(if (searchMode) "🔍" else "⏎", 1.5f, if (searchMode) ACCENT else KEY_DARK,
                if (searchMode) Color.BLACK else Color.WHITE) { enter() }
        )
        keysPanel.addView(row(bottom, 0))
    }

    private fun type(s: String) {
        if (searchMode) {
            searchBuf.append(s)
            renderSearchBar()
            return
        }
        currentInputConnection?.commitText(s, 1)
        if (s.length == 1 && (s[0].isLetter() || s[0] == '\'')) currentWord += s else currentWord = ""
        if (currentWord.isEmpty()) showSuggestions(emptyList()) else scheduleSuggestions()
    }

    private fun deleteOne() {
        if (searchMode) {
            if (searchBuf.isNotEmpty()) searchBuf.deleteCharAt(searchBuf.length - 1)
            renderSearchBar()
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        currentWord = currentWord.dropLast(1)
        if (currentWord.isEmpty()) showSuggestions(emptyList()) else scheduleSuggestions()
    }

    private fun enter() {
        if (searchMode) {
            doSearch()
            return
        }
        if (!sendDefaultEditorAction(true)) currentInputConnection?.commitText("\n", 1)
        currentWord = ""
        showSuggestions(emptyList())
    }

    // ---------- GIF search ----------

    private fun renderSearchBar() {
        strip.removeAllViews()
        val t = TextView(this)
        val empty = searchBuf.isEmpty()
        t.text = if (empty) "🔍  Type to search GIFs…" else "🔍  $searchBuf▏"
        t.setTextColor(if (empty) Color.GRAY else Color.WHITE)
        t.textSize = 16f
        t.gravity = Gravity.CENTER_VERTICAL
        t.setPadding(dp(12), 0, dp(12), 0)
        t.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        strip.addView(t)
    }

    private fun startSearch() {
        searchMode = true
        searchBuf.setLength(0)
        buildKeys()
        showKeys()
    }

    private fun cancelSearch() {
        searchMode = false
        searchBuf.setLength(0)
        buildKeys()
        showSuggestions(emptyList())
        keysPanel.visibility = View.GONE
        gifPanel.visibility = View.VISIBLE
    }

    private fun doSearch() {
        val q = searchBuf.toString().trim()
        searchMode = false
        searchBuf.setLength(0)
        buildKeys()
        showSuggestions(emptyList())
        keysPanel.visibility = View.GONE
        gifPanel.visibility = View.VISIBLE
        if (q.isNotEmpty()) setQuery(q)
    }

    // ---------- word suggestions ----------

    private fun scheduleSuggestions() {
        ui.removeCallbacks(suggestRun)
        if (searchMode || spell == null) return
        ui.postDelayed(suggestRun, 120)
    }

    private fun runSpell() {
        val w = currentWord
        if (w.length < 2) {
            showSuggestions(emptyList())
            return
        }
        lastQueried = w
        try {
            spell?.getSentenceSuggestions(arrayOf(TextInfo(w)), 3)
        } catch (e: Exception) {
            showSuggestions(emptyList())
        }
    }

    private fun showSuggestions(list: List<String>) {
        if (!::strip.isInitialized || searchMode) return
        strip.removeAllViews()
        list.forEach { s ->
            val t = TextView(this)
            t.text = s
            t.setTextColor(Color.WHITE)
            t.textSize = 16f
            t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            t.tag = KeyAction({ applySuggestion(s) })
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
        top.addView(chip("🔍 Search", true) { startSearch() })

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

    private fun settingsSig() = provider() + "|" + prefs.getInt("max_age_days", 60)

    private fun ageLabel(ts: Long): String {
        if (ts <= 0) return ""
        val d = (System.currentTimeMillis() - ts) / 86_400_000L
        return when {
            d < 1 -> "today"
            d < 30 -> "${d}d"
            d < 365 -> "${d / 30}mo"
            else -> "${d / 365}y"
        }
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
            loadedSig = settingsSig()
            loadedAt = System.currentTimeMillis()
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
                if (adapter.itemCount >= MIN_FILL || autoPages >= 4) {
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
        val info = currentInputEditorInfo
        if (info == null) {
            toast("No text box is open")
            return
        }
        val mimes = EditorInfoCompat.getContentMimeTypes(info).toList()
        fun accepts(m: String) = mimes.any { ClipDescription.compareMimeTypes(m, it) }

        // WhatsApp-style GIFs are looping MP4 videos. Use MP4 when the app accepts it,
        // otherwise fall back to a normal GIF file.
        val wantMp4 = item.mp4Url != null && accepts("video/mp4")
        val wantGif = accepts("image/gif")
        if (!wantMp4 && !wantGif) {
            toast("This app doesn't accept GIFs. It accepts: " + mimes.joinToString().ifBlank { "nothing" })
            return
        }
        val url = if (wantMp4) item.mp4Url!! else item.gifUrl
        val mime = if (wantMp4) "video/mp4" else "image/gif"
        val ext = if (wantMp4) "mp4" else "gif"
        toast("Sending…")

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
                    if (ic == null || ei == null) {
                        toast("Lost the text box, tap it and try again")
                        return@post
                    }
                    val content = InputContentInfoCompat(uri, ClipDescription(item.id, arrayOf(mime)), null)
                    val ok = InputConnectionCompat.commitContent(
                        ic, ei, content,
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
                    )
                    toast(if (ok) "Sent as $ext" else "The app rejected this $ext")
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

        inner class VH(val box: FrameLayout, val iv: ImageView, val tag: TextView) : RecyclerView.ViewHolder(box)

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
            val box = FrameLayout(parent.context)
            box.layoutParams = RecyclerView.LayoutParams(MATCH, dp(110))
            box.setPadding(dp(1), dp(1), dp(1), dp(1))
            val iv = ImageView(parent.context)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            box.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            val tag = TextView(parent.context)
            tag.textSize = 10f
            tag.setTextColor(Color.WHITE)
            tag.setBackgroundColor(Color.parseColor("#99000000"))
            tag.setPadding(dp(4), dp(1), dp(4), dp(1))
            box.addView(tag, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
            return VH(box, iv, tag)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            Glide.with(applicationContext)
                .load(item.previewUrl)
                .placeholder(ColorDrawable(KEY))
                .into(holder.iv)
            val label = ageLabel(item.ts)
            holder.tag.text = label
            holder.tag.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
            holder.box.setOnClickListener { sendGif(item) }
        }

        override fun onViewRecycled(holder: VH) {
            Glide.with(applicationContext).clear(holder.iv)
        }
    }
}
