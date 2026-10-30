package acr.browser.lightning.browser.download

import acr.browser.lightning.R
import acr.browser.lightning.ThemableBrowserActivity
import acr.browser.lightning.browser.di.DatabaseScheduler
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.database.downloads.DownloadsRepository
import acr.browser.lightning.dialog.BrowserDialog.setDialogSize
import acr.browser.lightning.download.DownloadHandler
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.UserPreferences
import android.Manifest
import android.app.Dialog
import android.content.DialogInterface
import android.os.Build
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.greatfire.browser.download.DownloadEngine
import org.greatfire.browser.ui.DrBrowserSheets
import javax.inject.Inject

/**
 * Wraps [DownloadHandler] & [DownloadEngine] for proxy-tunneled multi-connection downloads
 * across Android 5.0 (API 21) through Android 16+ (API 35+) without legacy storage permission bugs.
 */
class DownloadPermissionsHelper @Inject constructor(
    @Suppress("unused") private val downloadHandler: DownloadHandler,
    @Suppress("unused") private val userPreferences: UserPreferences,
    private val logger: Logger,
    private val downloadsRepository: DownloadsRepository,
    @DatabaseScheduler private val databaseScheduler: Scheduler
) {

    fun download(
        activity: FragmentActivity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val runPrompt = {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getFileExtensionFromUrl(url).takeIf(String::isNotBlank)
                ?: "download_file"
            val downloadSize: String = if (contentLength > 0) {
                Formatter.formatFileSize(activity, contentLength)
            } else {
                activity.getString(R.string.unknown_size)
            }
            val dialogClickListener = DialogInterface.OnClickListener { _, which: Int ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        DownloadEngine.getInstance(activity).enqueueDownload(
                            url = url,
                            userAgent = userAgent.orEmpty(),
                            contentDisposition = contentDisposition.orEmpty(),
                            mimeType = mimeType.orEmpty(),
                            suggestedFileNameOverride = fileName
                        )
                        downloadsRepository.addDownloadIfNotExists(
                            DownloadEntry(
                                url = url,
                                title = fileName,
                                contentSize = downloadSize
                            )
                        ).subscribeOn(databaseScheduler)
                            .subscribeBy {
                                if (!it) {
                                    logger.log(TAG, "error saving download to database")
                                }
                            }
                        Toast.makeText(
                            activity,
                            "⬇ دانلود پرسرعت آغاز شد: $fileName",
                            Toast.LENGTH_SHORT
                        ).show()
                        DrBrowserSheets.showIdmDownloaderSheet(activity, null, initialTabDownloads = true)
                    }

                    DialogInterface.BUTTON_NEGATIVE -> Unit
                }
            }
            val builder = AlertDialog.Builder(activity)
            val message: String = activity.getString(R.string.dialog_download, downloadSize)
            val dialog: Dialog = builder.setTitle(fileName)
                .setMessage(message)
                .setPositiveButton(
                    activity.resources.getString(R.string.action_download),
                    dialogClickListener
                )
                .setNegativeButton(
                    activity.resources.getString(R.string.action_cancel),
                    dialogClickListener
                ).show()
            setDialogSize(activity, dialog)
            logger.log(TAG, "Downloading: $fileName")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runPrompt()
        } else {
            val needed = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val themable = activity as? ThemableBrowserActivity
            if (themable != null) {
                themable.requestRuntimePermissions(needed) { granted ->
                    if (granted) {
                        runPrompt()
                    } else {
                        Toast.makeText(activity, "مجوز ذخیره فایل داده نشد", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                runPrompt()
            }
        }
    }

    companion object {
        private const val TAG = "DownloadPermissionsHelper"
    }
}
