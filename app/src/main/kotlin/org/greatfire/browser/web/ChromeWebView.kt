package org.greatfire.browser.web

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.annotation.RequiresApi
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class WebContextMenuPayload(
    val linkUrl: String = "",
    val linkText: String = "",
    val imageUrl: String = "",
    val imageAlt: String = "",
    val hitType: Int = WebView.HitTestResult.UNKNOWN_TYPE
) {
    val hasLink: Boolean get() = linkUrl.isNotBlank()
    val hasImage: Boolean get() = imageUrl.isNotBlank()
}

/**
 * Authentic Chrome-parity WebView supporting:
 * 1. Reliable Long-Press Context Menu on ALL links (<a>, nested SPA cards, mailto:, tel:),
 *    linked images (<a><img></a>), and standalone images (<img>).
 * 2. Native Android Floating Text Selection ActionMode (Copy, Cut, Paste, Select All, Share)
 *    enhanced with Chrome's "جستجو در وب" (Web Search), "کپی سریع", & "اشتراک‌گذاری" actions.
 */
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

    @Volatile
    private var probedHref: String = ""

    @Volatile
    private var probedLinkText: String = ""

    @Volatile
    private var probedImgSrc: String = ""

    @Volatile
    private var probedImgAlt: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true

        setOnLongClickListener {
            handleLongPress()
        }
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

        @Suppress("DEPRECATION")
        val densityScale = if (scale > 0f) scale else resources.displayMetrics.density
        val cssX = (rawX / densityScale).toInt()
        val cssY = (rawY / densityScale).toInt()

        val js = """
            (function(x, y) {
                try {
                    var el = document.elementFromPoint(x, y);
                    if (!el) return '';
                    var tag = (el.tagName || '').toUpperCase();
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable) {
                        return JSON.stringify({ editable: true });
                    }
                    var a = el.closest ? el.closest('a[href]') : null;
                    var img = (tag === 'IMG') ? el : (a ? a.querySelector('img') : null);
                    var href = a ? (a.href || '') : '';
                    var txt = a ? ((a.innerText || a.textContent || a.title || '').replace(/\s+/g, ' ').trim().slice(0, 300)) : '';
                    var src = img ? (img.currentSrc || img.src || '') : '';
                    var alt = img ? ((img.alt || '').trim().slice(0, 200)) : '';
                    if (!href && !src) return '';
                    return JSON.stringify({ href: href, text: txt, src: src, alt: alt });
                } catch (e) {
                    return '';
                }
            })($cssX, $cssY);
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
                    }
                }
            }
        }
    }

    private fun handleLongPress(): Boolean {
        val hit = hitTestResult
        val type = hit?.type ?: HitTestResult.UNKNOWN_TYPE
        val extra = hit?.extra.orEmpty()

        // Never hijack long-press inside <input> or <textarea> so Paste / Select All works natively!
        if (type == HitTestResult.EDIT_TEXT_TYPE) {
            return false
        }

        val isNativeLinkOrImage = type == HitTestResult.SRC_ANCHOR_TYPE ||
            type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
            type == HitTestResult.IMAGE_TYPE ||
            type == HitTestResult.EMAIL_TYPE ||
            type == HitTestResult.PHONE_TYPE

        val hasProbedLinkOrImage = probedHref.isNotBlank() || probedImgSrc.isNotBlank()

        if (!isNativeLinkOrImage && !hasProbedLinkOrImage) {
            // Plain text on page -> return false so Chromium activates blue selection handles + floating Copy/Share toolbar!
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
                hitType = type
            )
            if (payload.hasLink || payload.hasImage) {
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
        // Fallback in case Chromium AwContents skips sending Message on standalone <img> or SPA card links
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
            // Add explicit Persian share option in case system OEM omits Share
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

@RequiresApi(Build.VERSION_CODES.M)
private object Api23ActionModeHelper {
    fun wrap(
        delegate: ActionMode.Callback,
        onActiveChanged: (Boolean) -> Unit,
        onAugmentMenu: (Menu) -> Unit,
        onItemClicked: (ActionMode, MenuItem) -> Boolean
    ): ActionMode.Callback {
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

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
                if (delegate is ActionMode.Callback2) {
                    delegate.onGetContentRect(mode, view, outRect)
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
            }
        }
    }
}
