package org.greatfire.browser.web

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

/**
 * Chrome-grade SwipeRefreshLayout that NEVER cancels WebView 500ms long-press gestures
 * on links/text/images, NEVER steals multi-touch pinch-zoom, and NEVER interferes while
 * text-selection handles (ActionMode) are active.
 */
class BrowserSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var multiTouchDetected = false

    var blockInterceptChecker: (() -> Boolean)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (blockInterceptChecker?.invoke() == true) {
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                multiTouchDetected = ev.pointerCount > 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouchDetected = true
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouchDetected || ev.pointerCount > 1) {
                    return false
                }
                val dx = abs(ev.x - downX)
                val dy = ev.y - downY
                // Require deliberate downward pull (> 2.8x touchSlop & vertical angle) so slight
                // thumb tremor during a 500ms long-press never cancels WebView link/text selection!
                if (dy < touchSlop * 2.8f || dy < dx * 2.0f) {
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                multiTouchDetected = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
