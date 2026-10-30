package com.example.magremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            // Подключаем мост для связи JavaScript с Kotlin
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
        }

        setContentView(webView)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            webView.loadUrl("file:///android_asset/index.html")
            return
        }

        val action = intent.action
        val type = intent.type

        // Обработка входящей ссылки из меню "Поделиться" (Share)
        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrEmpty()) {
                    val encodedText = Uri.encode(sharedText)
                    webView.loadUrl("file:///android_asset/index.html?url=$encodedText")
                    return
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    // Мост для выполнения поиска прямо с телефона без нагрузки на сервер
    inner class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun searchVideos(query: String, callbackName: String) {
            executor.execute {
                try {
                    // Делаем легкий HTTP-запрос к публичному открытому API Invidious прямо с телефона
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val url = URL("https://vid.puffyan.us/api/v1/search?q=$encodedQuery")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000

                    val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JsonParser.parseString(responseString).asJsonArray
                    val resultsList = mutableListOf>()

                    for (element in jsonArray) {
                        val obj = element.asJsonObject
                        if (obj.get("type")?.asString == "video") {
                            val videoId = obj.get("videoId")?.asString ?: ""
                            val title = obj.get("title")?.asString ?: "Видео"
                            resultsList.add(
                                mapOf(
                                    "id" to videoId,
                                    "title" to title,
                                    "thumbnail" to "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
                                    "type" to "video"
                                )
                            )
                            if (resultsList.size >= 12) break
                        }
                    }

                    val jsonResult = gson.toJson(resultsList)
                    activity.runOnUiThread {
                        webView.evaluateJavascript("javascript:$callbackName($jsonResult)", null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    activity.runOnUiThread {
                        webView.evaluateJavascript("javascript:$callbackName([])", null)
                    }
                }
            }
        }
    }
}
