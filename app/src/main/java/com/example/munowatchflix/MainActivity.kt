package com.example.munowatchflix

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout

    // 🔥 YOUR SECRET KEY AND IV
    private val SECRET_KEY = "xK9\$mP2!vL7&qR4*"
    private val INIT_VECTOR = "0000000000000000"

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        filePathCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Force Fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // 2. Setup the Layout
        val rootLayout = FrameLayout(this)
        webView = WebView(this)
        rootLayout.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        fullscreenContainer = FrameLayout(this)
        fullscreenContainer.visibility = View.GONE
        rootLayout.addView(fullscreenContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        setContentView(rootLayout)

        // 3. Configure WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false 
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 4. Add Javascript Bridge for Downloads
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // 5. Handle Links
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (url.startsWith("http:") || url.startsWith("https:")) {
                    return false 
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    return true
                }
            }
        }

        // 6. Video Fullscreen & File Uploads
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams?.createIntent())
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // 7. Native Downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimetype)
            request.addRequestHeader("User-Agent", userAgent)
            request.setDescription("Downloading file...")
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
            request.allowScanningByMediaScanner()
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
            
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }

        // 🔥 8. DECRYPT AND LOAD THE HTML
        try {
            val inputStream = assets.open("encrypted.txt")
            val encryptedHtml = inputStream.bufferedReader().use { it.readText() }
            val decryptedHtml = decryptAES(encryptedHtml, SECRET_KEY)
            
            webView.loadDataWithBaseURL("file:///android_asset/", decryptedHtml, "text/html", "UTF-8", null)
            
        } catch (e: Exception) {
            webView.loadData("<html><body style='background:#000;color:red;padding:20px;font-family:sans-serif;'><h2>Error Loading App</h2><p>Could not decrypt app data. Please reinstall.</p></body></html>", "text/html", "UTF-8")
            Toast.makeText(this, "Decryption failed!", Toast.LENGTH_LONG).show()
        }
    }

    // 🔥 THE DECRYPTION FUNCTION
    private fun decryptAES(encryptedText: String, key: String): String {
        try {
            val ivSpec = IvParameterSpec(INIT_VECTOR.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            
            // Decode Base64 and decrypt
            val decodedBytes = Base64.decode(encryptedText.trim(), Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Decryption failed: ${e.message}")
        }
    }

    // 🔥 9. THE BRIDGE CLASS FOR DOWNLOADS
    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun downloadFile(url: String, fileName: String) {
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setTitle(fileName)
                request.setDescription("Downloading...")
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                
                (context as MainActivity).runOnUiThread {
                    Toast.makeText(context, "Download started!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                (context as MainActivity).runOnUiThread {
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun saveBlobToDownloads(base64Data: String, fileName: String, mimeType: String) {
            try {
                val decodedBytes = Base64.decode(base64Data.split(",")[1], Base64.DEFAULT)
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val fos = FileOutputStream(file)
                fos.write(decodedBytes)
                fos.close()
                
                (context as MainActivity).runOnUiThread {
                    Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                (context as MainActivity).runOnUiThread {
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
