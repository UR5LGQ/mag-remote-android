package com.example.magremote

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import java.io.IOException
import java.util.concurrent.Executors

// Простой реализатор Downloader для NewPipeExtractor на базе HttpURLConnection
class CustomDownloader : Downloader() {
    @Throws(IOException::class)
    override function execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val url = request.url()
        val httpMethod = request.httpMethod()
        val headers = request.headers()
        
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = httpMethod
        headers.forEach { (key, values) ->
            values.forEach { value -> connection.addRequestProperty(key, value) }
        }
        
        val code = connection.responseCode
        val responseMessage = connection.responseMessage
        val inputStream = if (code in 200..299) connection.inputStream else connection.errorStream
        
        val body = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
        val responseHeaders = connection.headerFields.mapValues { it.value }
        
        return Response(code, responseMessage, responseHeaders, body, connection.url.toString())
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Инициализируем NewPipeExtractor один раз при старте
        try {
            NewPipe.init(CustomDownloader())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            
            // Регистрируем мост для JS под именем "AndroidBridge"
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
        }
        
        setContentView(webView)

        // Загружаем ваш phone.html из папки assets
        webView.loadUrl("file:///android_asset/phone.html")
    }

    // Класс, методы которого будут доступны из JavaScript на странице phone.html
    inner class WebAppInterface(private val activity: MainActivity) {

        @JavascriptInterface
        fun searchVideos(query: String, callbackName: String) {
            executor.execute {
                try {
                    // Используем YouTube сервис NewPipeExtractor для поиска
                    val ytService = NewPipe.getService(0) // 0 обычно YouTube в NewPipe
                    val searchLink = ytService.searchExtractor.from(query)
                    searchLink.fetchPage()
                    
                    val resultsList = searchLink.initialPage.items.take(10).map { item ->
                        mapOf(
                            "id" to item.url.substringAfter("v=").substringBefore("&"),
                            "title" to item.title,
                            "thumbnail" to item.thumbnailUrl,
                            "type" to "video"
                        )
                    }

                    val jsonResult = gson.toJson(resultsList)
                    
                    // Возвращаем результат обратно в JavaScript функцию
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