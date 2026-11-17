package com.tokisync.viewer.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 앱 전역 싱글톤 모음 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // GAS 는 라이브러리 재구성 시 수 분이 걸릴 수 있음
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val settings = SettingsStore(context)
    val gas = GasClient(settings, http)
    val history = HistoryStore(context, appScope)
    val episodes = EpisodeStore(context, gas, http, settings)
    val thumbs = ThumbnailRepo(context, gas, http)
    val cache = LocalCache(context)
}
