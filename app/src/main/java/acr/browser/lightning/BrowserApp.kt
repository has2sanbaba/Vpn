package acr.browser.lightning

import acr.browser.lightning.browser.di.AppComponent
import acr.browser.lightning.browser.di.DaggerAppComponent
import acr.browser.lightning.browser.di.DatabaseScheduler
import acr.browser.lightning.browser.di.injector
import acr.browser.lightning.browser.proxy.ProxyAdapter
import acr.browser.lightning.database.bookmark.BookmarkExporter
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.log.Logger
import acr.browser.lightning.migration.Cleanup
import acr.browser.lightning.utils.FileUtils
import acr.browser.lightning.utils.LeakCanaryUtils
import android.app.Application
import android.os.StrictMode
import android.webkit.WebView
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.greatfire.browser.vpn.TunnelManager
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * The browser application (Lightning-Browser MPL-2.0 base + GreatFire/Envoy in-process JNI engine).
 */
class BrowserApp : Application() {

    @Inject
    internal lateinit var leakCanaryUtils: LeakCanaryUtils

    @Inject
    internal lateinit var bookmarkModel: BookmarkRepository

    @Inject
    @DatabaseScheduler
    internal lateinit var databaseScheduler: Scheduler

    @Inject
    internal lateinit var logger: Logger

    @Inject
    internal lateinit var buildInfo: BuildInfo

    @Inject
    internal lateinit var proxyAdapter: ProxyAdapter

    @Inject
    internal lateinit var cleanup: Cleanup

    lateinit var applicationComponent: AppComponent

    override fun onCreate() {
        super.onCreate()

        // Start in-process JNI anti-censorship + 50-source smart proxy engine at t=0ms with zero UI blocking
        TunnelManager.getInstance(this).initializeAutoStart()

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        MainScope().launch {
            cleanup.cleanup()
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(ex)
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                exitProcess(2)
            }
        }

        RxJavaPlugins.setErrorHandler { throwable: Throwable? ->
            if (BuildConfig.DEBUG && throwable != null) {
                FileUtils.writeCrashToStorage(throwable)
                throw throwable
            }
        }

        applicationComponent = DaggerAppComponent.builder()
            .application(this)
            .buildInfo(createBuildInfo())
            .build()
        injector.inject(this)

        Single.fromCallable(bookmarkModel::count)
            .filter { it == 0L }
            .flatMapCompletable {
                val assetsBookmarks = BookmarkExporter.importBookmarksFromAssets(this@BrowserApp)
                bookmarkModel.addBookmarkList(assetsBookmarks)
            }
            .subscribeOn(databaseScheduler)
            .subscribe()

        if (buildInfo.buildType == BuildType.DEBUG) {
            leakCanaryUtils.setup()
            WebView.setWebContentsDebuggingEnabled(true)
        }

        registerActivityLifecycleCallbacks(proxyAdapter)
    }

    private fun createBuildInfo() = BuildInfo(
        when {
            BuildConfig.DEBUG -> BuildType.DEBUG
            else -> BuildType.RELEASE
        }
    )

    companion object {
        private const val TAG = "BrowserApp"
    }
}
