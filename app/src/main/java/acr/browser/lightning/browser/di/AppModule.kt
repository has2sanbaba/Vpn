package acr.browser.lightning.browser.di

import acr.browser.lightning.R
import acr.browser.lightning.browser.tab.DefaultTabTitle
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.favicon.FaviconCleanup
import acr.browser.lightning.html.ListPageReader
import acr.browser.lightning.html.bookmark.BookmarkCleanup
import acr.browser.lightning.html.bookmark.BookmarkPageReader
import acr.browser.lightning.html.download.DownloadCleanup
import acr.browser.lightning.html.history.HistoryCleanup
import acr.browser.lightning.html.homepage.HomeCleanup
import acr.browser.lightning.html.homepage.HomePageReader
import acr.browser.lightning.js.InvertPage
import acr.browser.lightning.js.TextReflow
import acr.browser.lightning.js.ThemeColor
import acr.browser.lightning.log.AndroidLogger
import acr.browser.lightning.log.Logger
import acr.browser.lightning.log.NoOpLogger
import acr.browser.lightning.migration.Cleanup
import acr.browser.lightning.search.suggestions.RequestFactory
import acr.browser.lightning.utils.FileUtils
import acr.browser.lightning.utils.ThreadSafeFileProvider
import android.app.ActivityManager
import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.greatfire.browser.vpn.LegacySslTrustBridge
import org.greatfire.browser.vpn.TunnelManager
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
class AppModule {

    @Provides
    @MainHandler
    fun provideMainHandler() = Handler(Looper.getMainLooper())

    @Provides
    fun provideContext(application: Application): Context = application.applicationContext

    @Provides
    @UserPrefs
    fun provideUserPreferences(application: Application): SharedPreferences =
        application.getSharedPreferences("settings", 0)

    @Provides
    @DevPrefs
    fun provideDebugPreferences(application: Application): SharedPreferences =
        application.getSharedPreferences("developer_settings", 0)

    @Provides
    @AdBlockPrefs
    fun provideAdBlockPreferences(application: Application): SharedPreferences =
        application.getSharedPreferences("ad_block_settings", 0)

    @Provides
    fun providesAssetManager(application: Application): AssetManager = application.assets

    @Provides
    fun providesClipboardManager(application: Application): ClipboardManager =
        ContextCompat.getSystemService(application, ClipboardManager::class.java)
            ?: (application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)

    @Provides
    fun providesInputMethodManager(application: Application): InputMethodManager =
        ContextCompat.getSystemService(application, InputMethodManager::class.java)
            ?: (application.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)

    @Provides
    fun providesDownloadManager(application: Application): DownloadManager =
        ContextCompat.getSystemService(application, DownloadManager::class.java)
            ?: (application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)

    @Provides
    fun providesConnectivityManager(application: Application): ConnectivityManager =
        ContextCompat.getSystemService(application, ConnectivityManager::class.java)
            ?: (application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)

    @Provides
    fun providesNotificationManager(application: Application): NotificationManager =
        ContextCompat.getSystemService(application, NotificationManager::class.java)
            ?: (application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    @Provides
    fun providesWindowManager(application: Application): WindowManager =
        ContextCompat.getSystemService(application, WindowManager::class.java)
            ?: (application.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

    @Provides
    fun providesActivityManager(application: Application): ActivityManager =
        ContextCompat.getSystemService(application, ActivityManager::class.java)
            ?: (application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)

    @Provides
    @DatabaseScheduler
    @Singleton
    fun providesIoThread(): Scheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    @Provides
    @DiskScheduler
    @Singleton
    fun providesDiskThread(): Scheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    @Provides
    @NetworkScheduler
    @Singleton
    fun providesNetworkThread(): Scheduler =
        Schedulers.from(ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, LinkedBlockingDeque()))

    @Provides
    @MainScheduler
    @Singleton
    fun providesMainThread(): Scheduler = AndroidSchedulers.mainThread()

    @Singleton
    @Provides
    fun providesSuggestionsCacheControl(): CacheControl =
        CacheControl.Builder().maxStale(1, TimeUnit.DAYS).build()

    @Singleton
    @Provides
    fun providesSuggestionsRequestFactory(cacheControl: CacheControl): RequestFactory =
        object : RequestFactory {
            override fun createSuggestionsRequest(httpUrl: HttpUrl, encoding: String): Request {
                return Request.Builder().url(httpUrl)
                    .addHeader("Accept-Charset", encoding)
                    .cacheControl(cacheControl)
                    .build()
            }
        }

    private fun createInterceptorWithMaxCacheAge(maxCacheAgeSeconds: Long) = Interceptor { chain ->
        chain.proceed(chain.request()).newBuilder()
            .header("cache-control", "max-age=$maxCacheAgeSeconds, max-stale=$maxCacheAgeSeconds")
            .build()
    }

    private fun configureTunnelAwareClient(
        application: Application,
        builder: OkHttpClient.Builder
    ): OkHttpClient.Builder {
        builder.proxySelector(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val host = uri?.host.orEmpty()
                if (host == "127.0.0.1" || host == "localhost") {
                    return listOf(Proxy.NO_PROXY)
                }
                val port = TunnelManager.getInstance(application).gatewayPort
                return if (port > 0) {
                    listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                } else {
                    listOf(Proxy.NO_PROXY)
                }
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            LegacySslTrustBridge.initialize(application)
            val sf = LegacySslTrustBridge.sslSocketFactory
            val tm = LegacySslTrustBridge.trustManager
            if (sf != null && tm != null) {
                builder.sslSocketFactory(sf, tm)
            }
        }
        return builder
    }

    @Singleton
    @Provides
    @SuggestionsClient
    fun providesSuggestionsHttpClient(application: Application): Single<OkHttpClient> =
        Single.fromCallable {
            val intervalDay = TimeUnit.DAYS.toSeconds(1)
            val suggestionsCache = File(application.cacheDir, "suggestion_responses")

            return@fromCallable configureTunnelAwareClient(
                application,
                OkHttpClient.Builder()
                    .cache(Cache(suggestionsCache, FileUtils.megabytesToBytes(1)))
                    .addNetworkInterceptor(createInterceptorWithMaxCacheAge(intervalDay))
            ).build()
        }.cache()

    @Singleton
    @Provides
    @HostsClient
    fun providesHostsHttpClient(application: Application): Single<OkHttpClient> =
        Single.fromCallable {
            val intervalYear = TimeUnit.DAYS.toSeconds(365)
            val suggestionsCache = File(application.cacheDir, "hosts_cache")

            return@fromCallable configureTunnelAwareClient(
                application,
                OkHttpClient.Builder()
                    .cache(Cache(suggestionsCache, FileUtils.megabytesToBytes(5)))
                    .addNetworkInterceptor(createInterceptorWithMaxCacheAge(intervalYear))
            ).build()
        }.cache()

    @Provides
    @Singleton
    fun provideLogger(buildInfo: BuildInfo): Logger = if (buildInfo.buildType == BuildType.DEBUG) {
        AndroidLogger()
    } else {
        NoOpLogger()
    }

    private fun readMezzanineAsset(application: Application, name: String): String =
        application.assets.open("mezzanine/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Provides
    @Singleton
    fun providesListPageReader(application: Application): ListPageReader {
        val cached by lazy { readMezzanineAsset(application, "list.html") }
        return object : ListPageReader {
            override fun provideHtml(): String = cached
        }
    }

    @Provides
    @Singleton
    fun providesHomePageReader(application: Application): HomePageReader {
        val cached by lazy { readMezzanineAsset(application, "homepage.html") }
        return object : HomePageReader {
            override fun provideHtml(): String = cached
        }
    }

    @Provides
    @Singleton
    fun providesBookmarkPageReader(application: Application): BookmarkPageReader {
        val cached by lazy { readMezzanineAsset(application, "bookmarks.html") }
        return object : BookmarkPageReader {
            override fun provideHtml(): String = cached
        }
    }

    @Provides
    @Singleton
    fun providesTextReflow(application: Application): TextReflow {
        val cached by lazy { readMezzanineAsset(application, "TextReflow.js") }
        return object : TextReflow {
            override fun provideJs(): String = cached
        }
    }

    @Provides
    @Singleton
    fun providesThemeColor(application: Application): ThemeColor {
        val cached by lazy { readMezzanineAsset(application, "ThemeColor.js") }
        return object : ThemeColor {
            override fun provideJs(): String = cached
        }
    }

    @Provides
    @Singleton
    fun providesInvertPage(application: Application): InvertPage {
        val cached by lazy { readMezzanineAsset(application, "InvertPage.js") }
        return object : InvertPage {
            override fun provideJs(): String = cached
        }
    }

    @DefaultTabTitle
    @Provides
    fun providesDefaultTabTitle(application: Application): String =
        application.getString(R.string.untitled)

    @Provides
    fun providesCleanupList(
        faviconCleanup: FaviconCleanup,
        bookmarkCleanup: BookmarkCleanup,
        downloadCleanup: DownloadCleanup,
        historyCleanup: HistoryCleanup,
        homeCleanup: HomeCleanup
    ): List<@JvmSuppressWildcards Cleanup.Action> =
        listOf(faviconCleanup, bookmarkCleanup, downloadCleanup, historyCleanup, homeCleanup)

    @FilesDir
    @Provides
    fun providesFilesDir(
        application: Application,
        threadSafeFileProviderFactory: ThreadSafeFileProvider.Factory
    ): ThreadSafeFileProvider = threadSafeFileProviderFactory.create {
        application.filesDir
    }

    @DataDir
    @Provides
    fun providesDataDir(
        application: Application,
        threadSafeFileProviderFactory: ThreadSafeFileProvider.Factory
    ): ThreadSafeFileProvider = threadSafeFileProviderFactory.create {
        ContextCompat.getDataDir(application) ?: File(application.applicationInfo.dataDir)
    }

    @CacheDir
    @Provides
    fun providesCacheDir(
        application: Application,
        threadSafeFileProviderFactory: ThreadSafeFileProvider.Factory
    ): ThreadSafeFileProvider = threadSafeFileProviderFactory.create {
        application.cacheDir
    }
}

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SuggestionsClient

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class HostsClient

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MainHandler

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class UserPrefs

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class AdBlockPrefs

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DevPrefs

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MainScheduler

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DiskScheduler

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class NetworkScheduler

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DatabaseScheduler

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class FilesDir

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DataDir

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class CacheDir
