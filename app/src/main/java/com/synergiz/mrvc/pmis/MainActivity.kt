package com.synergiz.mrvc.pmis

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.synergiz.mrvc.pmis.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    private lateinit var binding: ActivityMainBinding
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserIntent: Intent? = null
    private var pendingDownload: PendingDownload? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            val uris =
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.let { intent ->
                        WebChromeClient.FileChooserParams.parseResult(result.resultCode, intent)
                    }
                } else {
                    null
                }

            callback.onReceiveValue(uris)
            fileChooserCallback = null
        }
    private val uploadPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val chooserIntent = pendingFileChooserIntent
            pendingFileChooserIntent = null

            if (granted && chooserIntent != null) {
                launchFileChooserIntent(chooserIntent)
            } else {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = null
                Toast.makeText(this, R.string.read_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingDownload
            pendingDownload = null
            if (granted && request != null) {
                enqueueDownload(request)
            } else if (request != null) {
                Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        configureWebView(binding.webView)
        binding.webView.loadUrl(BuildConfig.PMIS_BASE_URL)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun configureWebView(webView: WebView) {
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.errorGroup.isVisible = false
                    binding.progress.isVisible = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progress.isVisible = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        binding.errorGroup.isVisible = true
                    }
                }
            }

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    binding.progress.setProgressCompat(newProgress, true)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    pendingFileChooserIntent = null

                    val chooserIntent =
                        try {
                            fileChooserParams.createIntent()
                        } catch (_: ActivityNotFoundException) {
                            Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                        }

                    if (needsReadPermissionForUpload() && !hasReadPermissionForUpload()) {
                        pendingFileChooserIntent = chooserIntent
                        uploadPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        return true
                    }

                    return launchFileChooserIntent(chooserIntent)
                }
            }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val request =
                PendingDownload(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                )

            if (needsLegacyStoragePermission() && !hasLegacyStoragePermission()) {
                pendingDownload = request
                storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                enqueueDownload(request)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        binding.retryButton.setOnClickListener {
            binding.errorGroup.isVisible = false
            webView.reload()
        }
    }

    private fun needsLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    private fun needsReadPermissionForUpload(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2

    private fun hasLegacyStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasReadPermissionForUpload(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    private fun launchFileChooserIntent(chooserIntent: Intent): Boolean {
        return try {
            fileChooserLauncher.launch(chooserIntent)
            true
        } catch (_: Exception) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    private fun enqueueDownload(requestData: PendingDownload) {
        val cookie = CookieManager.getInstance().getCookie(requestData.url)
        val guessedFileName =
            URLUtil.guessFileName(
                requestData.url,
                requestData.contentDisposition,
                requestData.mimeType,
            )

        val downloadRequest =
            DownloadManager.Request(Uri.parse(requestData.url)).apply {
                setTitle(guessedFileName)
                setDescription(getString(R.string.download_in_progress))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType(requestData.mimeType)
                addRequestHeader("Cookie", cookie)
                requestData.userAgent?.let { addRequestHeader("User-Agent", it) }
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedFileName)
                } else {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedFileName)
                }
            }

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(downloadRequest)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        pendingFileChooserIntent = null
        pendingDownload = null
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
