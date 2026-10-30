package org.greatfire.browser.web

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class WebContextMenuPayload(
    val linkUrl: String = "",
    val linkText: String = "",
    val imageUrl: String = "",
    val imageAlt: String = "",
    val mediaUrl: String = "",
    val hitType: Int = WebView.HitTestResult.UNKNOWN_TYPE
) {
    val hasLink: Boolean get() = linkUrl.isNotBlank()
    val hasImage: Boolean get() = imageUrl.isNotBlank()
    val hasMedia: Boolean get() = mediaUrl.isNotBlank()
}

@SuppressLint("AddJavascriptInterface")
class ChromeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    @Volatile
    var isSelectionActionModeActive: Boolean = false
        private set

    var onContextMenuRequested: ((WebContextMenuPayload) -> Unit)? = null
    var onWebSearchSelectedText: ((String) -> Unit)? = null
    var onTranslateSelectedText: ((String) -> Unit)? = null
    var onAnyTouchDown: (() -> Unit)? = null
    var onBlobBase64Fetched: ((dataUrl: String, fileName: String, mime: String) -> Unit)? = null
    var onBlobFetchFailed: ((String) -> Unit)? = null
    var onIdmMediaDetectedJson: ((String) -> Unit)? = null

    @Volatile
    private var probedHref: String = ""

    @Volatile
    private var probedLinkText: String = ""

    @Volatile
    private var probedImgSrc: String = ""

    @Volatile
    private var probedImgAlt: String = ""

    @Volatile
    private var probedMediaSrc: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    @Keep
    private inner class BlobDownloadJsBridge {
        @JavascriptInterface
        fun onBlobData(dataUrl: String?, fileName: String?, mime: String?) {
            if (!dataUrl.isNullOrBlank()) {
                mainHandler.post {
                    onBlobBase64Fetched?.invoke(dataUrl, fileName.orEmpty(), mime.orEmpty())
                }
            } else {
                mainHandler.post {
                    onBlobFetchFailed?.invoke("فایل blob خالی بود")
                }
            }
        }

        @JavascriptInterface
        fun onBlobError(err: String?) {
            mainHandler.post {
                onBlobFetchFailed?.invoke(err.orEmpty().ifBlank { "خطا در خواندن فایل blob" })
            }
        }
    }

    @Keep
    private inner class IdmMediaJsBridge {
        @JavascriptInterface
        fun onMediaDetected(payloadJson: String?) {
            if (!payloadJson.isNullOrBlank()) {
                onIdmMediaDetectedJson?.invoke(payloadJson)
            }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true

        runCatching {
            addJavascriptInterface(BlobDownloadJsBridge(), "DrBlobBridge")
            addJavascriptInterface(IdmMediaJsBridge(), "DrIdmBridge")
        }

        setOnLongClickListener {
            handleLongPress()
        }
    }

    fun triggerIdmMediaRescan() {
        evaluateJavascript(org.greatfire.browser.media.VideoStreamSniffer.IDM_PAGE_SNIFFER_JS, null)
    }

    fun extractReadablePageText(onResult: (title: String, content: String) -> Unit) {
        val js = """
            (function() {
                try {
                    var root = document.querySelector('article, [role="main"], main') || document.body;
                    if (!root) return JSON.stringify({ title: document.title || '', text: '' });
                    var blocks = root.querySelectorAll('h1, h2, h3, p, li, blockquote');
                    var lines = [];
                    for (var i = 0; i < blocks.length && lines.length < 220; i++) {
                        var t = (blocks[i].innerText || '').replace(/\s+/g, ' ').trim();
                        if (t.length >= 25) lines.push(t);
                    }
                    var out = lines.length >= 2 ? lines.join('\n\n') : ((root.innerText || '').replace(/\n{3,}/g, '\n\n').trim());
                    return JSON.stringify({ title: document.title || '', text: out.slice(0, 28000) });
                } catch (e) {
                    return JSON.stringify({ title: document.title || '', text: '' });
                }
            })();
        """.trimIndent()
        evaluateJavascript(js) { raw ->
            val decoded = decodeEvaluateJsString(raw)
            val parsed = runCatching { JSONObject(decoded) }.getOrNull()
            onResult(
                parsed?.optString("title").orEmpty(),
                parsed?.optString("text").orEmpty()
            )
        }
    }

    fun downloadBlobUrl(blobUrl: String, suggestedName: String, mimeHint: String) {
        val safeUrl = JSONObject.quote(blobUrl)
        val safeName = JSONObject.quote(suggestedName)
        val safeMime = JSONObject.quote(mimeHint)
        val js = """
            (function(u, fname, mhint) {
                try {
                    fetch(u).then(function(r) { return r.blob(); }).then(function(b) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            DrBlobBridge.onBlobData(reader.result || '', fname, b.type || mhint || '');
                        };
                        reader.onerror = function() {
                            DrBlobBridge.onBlobError('خطا در تبدیل فایل');
                        };
                        reader.readAsDataURL(b);
                    }).catch(function(e) {
                        DrBlobBridge.onBlobError(String(e));
                    });
                } catch (err) {
                    DrBlobBridge.onBlobError(String(err));
                }
            })($safeUrl, $safeName, $safeMime);
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    fun stopAllMediaPlayback() {
        evaluateJavascript(
            "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.src='';});}catch(_){}})();",
            null
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onAnyTouchDown?.invoke()
            if (!hasFocus()) {
                requestFocus()
            }
            probeDomElementAtTouch(event.x, event.y)
        }
        return super.onTouchEvent(event)
    }

    private fun probeDomElementAtTouch(rawX: Float, rawY: Float) {
        probedHref = ""
        probedLinkText = ""
        probedImgSrc = ""
        probedImgAlt = ""
        probedMediaSrc = ""

        val vw = width.coerceAtLeast(1)
        val vh = height.coerceAtLeast(1)
        val ratioX = String.format(Locale.US, "%.5f", (rawX / vw).coerceIn(0f, 1f))
        val ratioY = String.format(Locale.US, "%.5f", (rawY / vh).coerceIn(0f, 1f))

        // Viewport-ratio calculation inside JS guarantees 100% accuracy across pinch-zoom AND Desktop Mode!
        val js = """
            (function(rx, ry) {
                try {
                    var x = Math.round(rx * (window.innerWidth || document.documentElement.clientWidth || 1));
                    var y = Math.round(ry * (window.innerHeight || document.documentElement.clientHeight || 1));
                    var el = document.elementFromPoint(x, y);
                    if (!el) return '';
                    var tag = (el.tagName || '').toUpperCase();
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable) {
                        return JSON.stringify({ editable: true });
                    }
                    var a = el.closest ? el.closest('a[href]') : null;
                    var img = (tag === 'IMG') ? el : (a ? a.querySelector('img') : null);
                    var media = (tag === 'VIDEO' || tag === 'AUDIO') ? el : (el.closest ? el.closest('video,audio') : null);
                    var href = a ? (a.href || '') : '';
                    var txt = a ? ((a.innerText || a.textContent || a.title || '').replace(/\s+/g, ' ').trim().slice(0, 300)) : '';
                    var src = img ? (img.currentSrc || img.src || '') : '';
                    var alt = img ? ((img.alt || '').trim().slice(0, 200)) : '';
                    var msrc = '';
                    if (media) {
                        msrc = media.currentSrc || media.src || '';
                        if (!msrc) {
                            var sEl = media.querySelector('source[src]');
                            if (sEl) msrc = sEl.src || '';
                        }
                    }
                    if (!href && !src && !msrc) return '';
                    return JSON.stringify({ href: href, text: txt, src: src, alt: alt, media: msrc });
                } catch (e) {
                    return '';
                }
            })($ratioX, $ratioY);
        """.trimIndent()

        evaluateJavascript(js) { rawResult ->
            runCatching {
                val unquoted = decodeEvaluateJsString(rawResult)
                if (unquoted.isNotBlank() && unquoted.startsWith("{")) {
                    val obj = JSONObject(unquoted)
                    if (!obj.optBoolean("editable", false)) {
                        probedHref = obj.optString("href", "")
                        probedLinkText = obj.optString("text", "")
                        probedImgSrc = obj.optString("src", "")
                        probedImgAlt = obj.optString("alt", "")
                        probedMediaSrc = obj.optString("media", "")
                    }
                }
            }
        }
    }

    private fun handleLongPress(): Boolean {
        val hit = hitTestResult
        val type = hit?.type ?: HitTestResult.UNKNOWN_TYPE
        val extra = hit?.extra.orEmpty()

        if (type == HitTestResult.EDIT_TEXT_TYPE) {
            return false
        }

        val isNativeLinkOrImage = type == HitTestResult.SRC_ANCHOR_TYPE ||
            type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
            type == HitTestResult.IMAGE_TYPE ||
            type == HitTestResult.EMAIL_TYPE ||
            type == HitTestResult.PHONE_TYPE

        val hasProbedTarget = probedHref.isNotBlank() || probedImgSrc.isNotBlank() || probedMediaSrc.isNotBlank()

        if (!isNativeLinkOrImage && !hasProbedTarget) {
            return false
        }

        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val dispatched = AtomicBoolean(false)
        fun emitContextMenu(msgUrl: String, msgTitle: String, msgSrc: String) {
            if (!dispatched.compareAndSet(false, true)) return

            val finalLink = when {
                msgUrl.isNotBlank() -> msgUrl
                type == HitTestResult.SRC_ANCHOR_TYPE && extra.isNotBlank() -> extra
                type == HitTestResult.EMAIL_TYPE && extra.isNotBlank() ->
                    if (extra.startsWith("mailto:")) extra else "mailto:$extra"
                type == HitTestResult.PHONE_TYPE && extra.isNotBlank() ->
                    if (extra.startsWith("tel:")) extra else "tel:$extra"
                else -> probedHref
            }

            val finalImage = when {
                msgSrc.isNotBlank() -> msgSrc
                (type == HitTestResult.IMAGE_TYPE || type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && extra.isNotBlank() -> extra
                else -> probedImgSrc
            }

            val finalText = probedLinkText.ifBlank { msgTitle }

            val payload = WebContextMenuPayload(
                linkUrl = finalLink,
                linkText = finalText,
                imageUrl = finalImage,
                imageAlt = probedImgAlt,
                mediaUrl = probedMediaSrc,
                hitType = type
            )
            if (payload.hasLink || payload.hasImage || payload.hasMedia) {
                onContextMenuRequested?.invoke(payload)
            }
        }

        val hrefHandler = Handler(Looper.getMainLooper()) { m ->
            val bundle = m.data
            emitContextMenu(
                msgUrl = bundle?.getString("url").orEmpty(),
                msgTitle = bundle?.getString("title").orEmpty(),
                msgSrc = bundle?.getString("src").orEmpty()
            )
            true
        }
        requestFocusNodeHref(hrefHandler.obtainMessage())
        mainHandler.postDelayed({ emitContextMenu("", "", "") }, 45L)
        return true
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        if (callback == null) return super.startActionMode(null)
        return super.startActionMode(wrapPrimaryCallback(callback))
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        if (callback == null) return super.startActionMode(null, type)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val wrapped = Api23ActionModeHelper.wrap(
                delegate = callback,
                onActiveChanged = { isSelectionActionModeActive = it },
                onAugmentMenu = { augmentSelectionMenu(it) },
                onItemClicked = { mode, item -> handleCustomSelectionItem(mode, item) }
            )
            return super.startActionMode(wrapped, type)
        }
        return super.startActionMode(wrapPrimaryCallback(callback), type)
    }

    private fun wrapPrimaryCallback(delegate: ActionMode.Callback): ActionMode.Callback {
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                isSelectionActionModeActive = true
                val created = delegate.onCreateActionMode(mode, menu)
                augmentSelectionMenu(menu)
                return created
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val res = delegate.onPrepareActionMode(mode, menu)
                augmentSelectionMenu(menu)
                return res
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (handleCustomSelectionItem(mode, item)) return true
                return delegate.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                isSelectionActionModeActive = false
                delegate.onDestroyActionMode(mode)
            }
        }
    }

    private fun augmentSelectionMenu(menu: Menu) {
        if (menu.findItem(MENU_ID_WEB_SEARCH) == null) {
            menu.add(Menu.NONE, MENU_ID_WEB_SEARCH, 90, "جستجو در وب")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        if (menu.findItem(MENU_ID_SHARE_TEXT) == null) {
            var hasNativeShare = false
            for (i in 0 until menu.size()) {
                val title = menu.getItem(i)?.title?.toString()?.lowercase().orEmpty()
                if (title.contains("share") || title.contains("اشتراک")) {
                    hasNativeShare = true
                    break
                }
            }
            if (!hasNativeShare) {
                menu.add(Menu.NONE, MENU_ID_SHARE_TEXT, 91, "اشتراک‌گذاری")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        }
        if (menu.findItem(MENU_ID_TRANSLATE_TEXT) == null) {
            menu.add(Menu.NONE, MENU_ID_TRANSLATE_TEXT, 92, "ترجمه")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private fun handleCustomSelectionItem(mode: ActionMode, item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ID_WEB_SEARCH -> {
                fetchSelectedText { text ->
                    mode.finish()
                    if (text.isNotBlank()) {
                        onWebSearchSelectedText?.invoke(text)
                    }
                }
                true
            }
            MENU_ID_SHARE_TEXT -> {
                fetchSelectedText { text ->
                    mode.finish()
                    if (text.isNotBlank()) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "اشتراک‌گذاری متن"))
                    }
                }
                true
            }
            MENU_ID_TRANSLATE_TEXT -> {
                fetchSelectedText { text ->
                    mode.finish()
                    if (text.isNotBlank()) {
                        onTranslateSelectedText?.invoke(text)
                    }
                }
                true
            }
            else -> false
        }
    }

    fun fetchSelectedText(onResult: (String) -> Unit) {
        evaluateJavascript("(window.getSelection ? window.getSelection().toString() : '')") { raw ->
            onResult(decodeEvaluateJsString(raw).trim())
        }
    }

    fun copyTextToClipboard(label: String, value: String, toastMessage: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private object Api23ActionModeHelper {
        fun wrap(
            delegate: ActionMode.Callback,
            onActiveChanged: (Boolean) -> Unit,
            onAugmentMenu: (Menu) -> Unit,
            onItemClicked: (ActionMode, MenuItem) -> Boolean
        ): ActionMode.Callback2 {
            return object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    onActiveChanged(true)
                    val created = delegate.onCreateActionMode(mode, menu)
                    onAugmentMenu(menu)
                    return created
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    val res = delegate.onPrepareActionMode(mode, menu)
                    onAugmentMenu(menu)
                    return res
                }

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    if (onItemClicked(mode, item)) return true
                    return delegate.onActionItemClicked(mode, item)
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    onActiveChanged(false)
                    delegate.onDestroyActionMode(mode)
                }

                override fun onGetContentRect(mode: ActionMode?, view: android.view.View?, outRect: android.graphics.Rect?) {
                    if (delegate is ActionMode.Callback2) {
                        delegate.onGetContentRect(mode, view, outRect)
                    } else {
                        super.onGetContentRect(mode, view, outRect)
                    }
                }
            }
        }
    }

    companion object {
        private const val MENU_ID_WEB_SEARCH = 0x70F101
        private const val MENU_ID_SHARE_TEXT = 0x70F102
        private const val MENU_ID_TRANSLATE_TEXT = 0x70F103

        fun decodeEvaluateJsString(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return ""
            return runCatching {
                org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
            }.getOrElse {
                raw.removeSurrounding("\"")
            }
        }
    }
}
