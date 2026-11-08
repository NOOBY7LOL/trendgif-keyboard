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
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private val BG = Color.parseColor("#1C1C1E")
private val KEY = Color.parseColor("#3A3A3C")
private val KEY_DARK = Color.parseColor("#2C2C2E")
private val ACCENT = Color.parseColor("#25D366")

class GifKeyboardService : InputMethodService() {

    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var keysPanel: LinearLayout
    private lateinit var gifPanel: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: GifAdapter

    private var shift = false
    private var symbols = false

    private var query: String? = null // null = trending
    private var offset = 0
    private var loading = false
    private var endReached = false
    private var generation = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        cleanCache()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)
        root.layoutParams = ViewGroup.LayoutParams(MATCH, dp(300))

        keysPanel = LinearLayout(this)
        keysPanel.orientation = LinearLayout.VERTICAL
        keysPanel.setPadding(dp(4), dp(6), dp(4), dp(6))

        gifPanel = buildGifPanel()
        gifPanel.visibility = View.GONE

        root.addView(keysPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(gifPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        buildKeys()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::keysPanel.isInitialized) showKeys()
    }

    // ---------- panels ----------

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
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    private fun enter() {
        if (!sendDefaultEditorAction(true)) type("\n")
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
                if (dy > 0 && !r.canScrollVertically(1)) loadGifs(false)
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

    private var loadedProvider = ""

    private fun provider(): String =
        getSharedPreferences("trendgif", MODE_PRIVATE).getString("provider", "klipy") ?: "klipy"

    private fun apiKey(prov: String): String =
        getSharedPreferences("trendgif", MODE_PRIVATE)
            .getString(if (prov == "giphy") "giphy_key" else "klipy_key", "").orEmpty().trim()

    private fun fetchPage(prov: String, key: String, q: String?, off: Int): List<GifItem> =
        if (prov == "giphy") {
            if (q == null) GiphyClient.trending(key, off) else GiphyClient.search(key, q, off)
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
        setStatus(if (offset == 0) "Loading…" else null)
        val gen = generation
        val q = query
        val off = offset
        io.execute {
            val result = try {
                fetchPage(prov, key, q, off)
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (gen != generation) return@post
                loading = false
                when {
                    result == null -> setStatus("Couldn't load GIFs. Check internet and API key")
                    result.isEmpty() -> {
                        endReached = true
                        setStatus(if (adapter.itemCount == 0) "No GIFs found" else null)
                    }
                    else -> {
                        offset += result.size
                        adapter.add(result)
                        setStatus(null)
                    }
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
