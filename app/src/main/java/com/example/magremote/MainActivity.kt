package com.example.magremote

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class CustomDownloader : Downloader() {
    @Throws(IOException::class)
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val url = request.url()
        val httpMethod = request.httpMethod()
        val headers = request.headers()
        
        val connection = URL(url).openConnection() as HttpURLConnection
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
        
        try {
            NewPipe.init(CustomDownloader())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
        }
        
        setContentView(webView)
        webView.loadUrl("file:///android_asset/phone.html")
    }

    inner class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun searchVideos(query: String, callbackName: String) {
            executor.execute {
                try {
                    val ytService = YoutubeService(0)
                    val searchExtractor = ytService.getSearchExtractor(query)
                    searchExtractor.fetchPage()
                    
                    val resultsList = searchExtractor.initialPage.items.take(10).map { item ->
                        val videoTitle = try { item.name } catch (e: Exception) { "Видео" }
                        val videoUrl = try { item.url } catch (e: Exception) { "" }
                        val thumbUrl = try { item.thumbnailUrl } catch (e: Exception) { "" }

                        mapOf(
                            "id" to videoUrl.substringAfter("v=").substringBefore("&"),
                            "title" to videoTitle,
                            "thumbnail" to thumbUrl,
                            "type" to "video"
                        )
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
