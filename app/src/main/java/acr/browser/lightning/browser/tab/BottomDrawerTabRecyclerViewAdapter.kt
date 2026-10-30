package acr.browser.lightning.browser.tab

import acr.browser.lightning.R
import acr.browser.lightning.browser.theme.ThemeProvider
import acr.browser.lightning.databinding.TabPreviewItemBinding
import acr.browser.lightning.extensions.drawable
import acr.browser.lightning.extensions.inflater
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight, low-RAM tab preview adapter (replaces heavy third-party image loader with a
 * bounded 4 MB LruCache + downsampled BitmapFactory decode on a single background thread).
 */
class BottomDrawerTabRecyclerViewAdapter(
    private val themeProvider: ThemeProvider,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onCloseClick: (Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onBackClick: (Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onForwardClick: (Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onHomeClick: (Int) -> Unit
) : ListAdapter<TabViewState, TabViewHolder>(
    object : DiffUtil.ItemCallback<TabViewState>() {
        override fun areItemsTheSame(oldItem: TabViewState, newItem: TabViewState): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TabViewState, newItem: TabViewState): Boolean =
            oldItem == newItem
    }
) {

    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): TabViewHolder {
        val tabPreviewItemBinding =
            TabPreviewItemBinding.inflate(viewGroup.context.inflater, viewGroup, false)

        return TabViewHolder(
            tabPreviewItemBinding.root,
            onClick = onClick,
            onLongClick = onLongClick,
            onCloseClick = onCloseClick
        ).apply {
            tabPreviewItemBinding.previewImage.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    onClick(pos)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.exitButton.tag = position

        val tab = getItem(position)

        holder.txtTitle.text = tab.title
        if (tab.isSelected) {
            holder.view.background =
                holder.view.context.drawable(R.drawable.tab_background_selected)
        } else {
            holder.view.background = holder.view.context.drawable(R.drawable.tab_background)
        }

        tab.icon?.let(holder.favicon::setImageBitmap)
            ?: holder.favicon.setImageResource(R.drawable.ic_webpage)
        loadImage(holder.itemView.findViewById(R.id.preview_image), tab)
    }

    private fun loadImage(imageView: ImageView, tab: TabViewState) {
        val placeholder = ColorDrawable(themeProvider.color(R.attr.colorPrimary))
        val path = tab.preview.first
        if (path.isNullOrBlank()) {
            imageView.tag = null
            imageView.setImageDrawable(placeholder)
            return
        }
        val cacheKey = "$path:${tab.preview.second}"
        imageView.tag = cacheKey
        previewCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) {
                imageView.setImageBitmap(cached)
                return
            }
        }

        imageView.setImageDrawable(placeholder)
        val reqW = max(imageView.width, 360)
        val reqH = max(imageView.height, 240)
        decodeExecutor.execute {
            val bmp = decodeTopCropped(path, reqW, reqH) ?: return@execute
            previewCache.put(cacheKey, bmp)
            mainHandler.post {
                if (imageView.tag == cacheKey && !bmp.isRecycled) {
                    imageView.setImageBitmap(bmp)
                }
            }
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tab-preview-decoder").apply { isDaemon = true }
        }
        private val previewCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

        private fun decodeTopCropped(path: String, dstW: Int, dstH: Int): Bitmap? {
            return try {
                val file = File(path)
                if (!file.exists()) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= dstW) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val raw = BitmapFactory.decodeFile(path, opts) ?: return null
                val scaledH = max(1, (dstW * (raw.height / raw.width.toFloat())).roundToInt())
                val scaled = if (raw.width != dstW) {
                    Bitmap.createScaledBitmap(raw, dstW, scaledH, true).also {
                        if (it !== raw) raw.recycle()
                    }
                } else {
                    raw
                }
                val cropH = min(scaled.height, dstH)
                if (cropH < scaled.height && cropH > 0) {
                    Bitmap.createBitmap(scaled, 0, 0, scaled.width, cropH).also {
                        if (it !== scaled) scaled.recycle()
                    }
                } else {
                    scaled
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
