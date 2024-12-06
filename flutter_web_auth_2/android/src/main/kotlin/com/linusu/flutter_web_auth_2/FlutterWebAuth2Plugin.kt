package com.linusu.flutter_web_auth_2

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class FlutterWebAuth2Plugin(
    private var context: Context? = null,
    private var channel: MethodChannel? = null
) : MethodCallHandler, FlutterPlugin {
    companion object {
        val callbacks = mutableMapOf<String, Result>()
    }

    private fun initInstance(messenger: BinaryMessenger, context: Context) {
        this.context = context
        channel = MethodChannel(messenger, "flutter_web_auth_2")
        channel?.setMethodCallHandler(this)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        initInstance(binding.binaryMessenger, binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
        channel = null
    }

    override fun onMethodCall(call: MethodCall, resultCallback: Result) {
        when (call.method) {
            "authenticate" -> {
                val url = Uri.parse(call.argument("url"))
                val callbackUrlScheme = call.argument<String>("callbackUrlScheme")!!
                val options = call.argument<Map<String, Any>>("options")!!

                callbacks[callbackUrlScheme] = resultCallback
               val builder = CustomTabsIntent.Builder().apply {
                    setDefaultColorSchemeParams(
                        CustomTabColorSchemeParams.Builder()
                            .setToolbarColor(ContextCompat.getColor(context, R.color.your_color))
                            .build()
                    )
                }

                // Clear session cookies and history
                val headers = Bundle().apply {
                    putString("Clear-Data", "true")
                }

                // Create and configure the intent
                val customTabsIntent = builder.build().apply {
                    intent.putExtra(Browser.EXTRA_HEADERS, headers)
                    
                    // Optional: Add flags to prevent history
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    
                }
                CustomTabsClient.connectAndInitialize(context, packageName)?.newSession(CustomTabsCallback())

                // Clear data when closing
                customTabsIntent.intent.putExtra("android.intent.extra.CLEAR_WHEN_TASK_RESET", true)

                // Launch with clean session    
                customTabsIntent.launchUrl(context!!, url)
                // val targetPackage = findTargetBrowserPackageName(options)
                // if (targetPackage != null) {
                //     intent.intent.setPackage(targetPackage)
                // }
                // intent.launchUrl(context!!, url)
            }

            "cleanUpDanglingCalls" -> {
                callbacks.forEach { (_, danglingResultCallback) ->
                    danglingResultCallback.error("CANCELED", "User canceled login", null)
                }
                callbacks.clear()
                resultCallback.success(null)
            }

            else -> resultCallback.notImplemented()
        }
    }

    /**
     * Find Support CustomTabs Browser.
     *
     * Priority:
     * 1. Chrome
     * 2. Custom Browser Order
     * 3. default Browser
     * 4. Installed Browser
     */
    private fun findTargetBrowserPackageName(options: Map<String, Any>): String? {
        @Suppress("UNCHECKED_CAST")
        val customTabsPackageOrder = (options["customTabsPackageOrder"] as Iterable<String>?) ?: emptyList()
        // Check target browser
        var targetPackage = customTabsPackageOrder.firstOrNull { isSupportCustomTabs(it) }
        if (targetPackage != null) {
            return targetPackage
        }

        // Check default browser
        val defaultBrowserSupported = CustomTabsClient.getPackageName(context!!, emptyList<String>()) != null
        if (defaultBrowserSupported) {
            return null;
        }
        // Check installed browser
        val allBrowsers = getInstalledBrowsers()
        targetPackage = allBrowsers.firstOrNull { isSupportCustomTabs(it) }

        // Safely fall back on Chrome just in case
        val chromePackage = "com.android.chrome"
        if (targetPackage == null && isSupportCustomTabs(chromePackage)) {
            return chromePackage
        }
        return targetPackage
    }

    private fun getInstalledBrowsers(): List<String> {
        // Get all apps that can handle VIEW intents
        val activityIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
        val packageManager = context!!.packageManager
        val viewIntentHandlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            packageManager.queryIntentActivities(activityIntent, PackageManager.MATCH_ALL)
        } else {
            packageManager.queryIntentActivities(activityIntent, 0)
        }

        val allBrowser = viewIntentHandlers.map { it.activityInfo.packageName }.sortedWith(compareBy {
            if (setOf(
                    "com.android.chrome",
                    "com.chrome.beta",
                    "com.chrome.dev",
                    "com.microsoft.emmx"
                ).contains(it)
            ) {
                return@compareBy -1
            }

            // Firefox default is not enabled, must enable in the browser settings.
            if (setOf("org.mozilla.firefox").contains(it)) {
                return@compareBy 1
            }
            return@compareBy 0
        })

        return allBrowser
    }

    private fun isSupportCustomTabs(packageName: String): Boolean {
        val value = CustomTabsClient.getPackageName(
            context!!,
            arrayListOf(packageName),
            true
        )
        return value == packageName
    }

    private fun startOAuth2Flow(authUrl: String, redirectUri: String, result: Result) {
        val webView = WebView(context!!)
        // Configure WebView settings
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = false
        webSettings.cacheMode = WebSettings.LOAD_NO_CACHE

        // Clear cookies and cache
        webView.clearCache(true)
        webView.clearHistory()

        // Set a WebViewClient to handle redirects
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (url.startsWith(redirectUri)) {
                    // Handle the redirect and extract the authorization code or token
                    handleRedirect(result, webView, url)
                    return true
                }
                return false
            }
        }

        // Load the OAuth2 authorization URL
        webView.loadUrl(authUrl)
    }

    private fun handleRedirect(result: Result, webView: WebView, url: String) {
        // Extract the authorization code or token from the URL
        val uri = Uri.parse(url)
        val authorizationCode = uri.getQueryParameter("code")

        // Exchange the authorization code for an access token
        // Implement your token exchange logic here

        // Notify the result back to Flutter
        // You might want to use a MethodChannel to send the result back
        result?.success(url)
        //close webview
        webView.destroy()
    }

}
