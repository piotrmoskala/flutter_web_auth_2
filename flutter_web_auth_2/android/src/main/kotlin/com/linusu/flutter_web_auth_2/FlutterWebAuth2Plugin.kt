package com.linusu.flutter_web_auth_2

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.*

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

import android.os.Bundle
import android.provider.Browser
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabColorSchemeParams

class FlutterWebAuth2Plugin(
    private var context: Context? = null, private var channel: MethodChannel? = null
) : MethodCallHandler, FlutterPlugin {
    companion object {
        val callbacks = mutableMapOf<String, Result>()
    }

    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null
    private var url: Uri? = null
    private var callbackUrlScheme: String? = null
    private var options: Map<String, Any>? = null
    private var resultCallback: Result? = null

    var connection: CustomTabsServiceConnection? = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            customTabsClient = client.apply {
                warmup(0)  // Warm up the browser process
            }
            println("Binding custom tabs service done")
            // Create new session
            customTabsSession = customTabsClient?.newSession(customTabsCallback)
            launchCustomTabs()
            unbindCustomTabsService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            customTabsClient = null
            println("Binding custom tabs service disconnected")
        }
    }

    private val customTabsCallback = object : CustomTabsCallback() {
        override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
            println("onNavigationEvent: $navigationEvent")
            when (navigationEvent) {
                ACTIVITY_LAYOUT_STATE_BOTTOM_SHEET -> {
                    //unbindCustomTabsService()
                }
                NAVIGATION_FINISHED -> {
                    // Tab finished loading
                    println("Tab finished loading")
                }

                TAB_HIDDEN -> {
                    // Tab was hidden (user switched away)
                    println("Tab was hidden")
                }

                TAB_SHOWN -> {
                    // Tab was shown again
                    println("Tab was shown again")
                }

                NAVIGATION_FAILED -> {
                    // Navigation failed
                    println("Navigation failed")
                }
            }
        }
    }

    private fun unbindCustomTabsService() {
        connection?.let { conn ->
            context?.let { ctx ->
                try {
                    println("Unbinding custom tabs service")
                    ctx.unbindService(conn)
                    //connection = null

                } catch (e: IllegalArgumentException) {
                    println("Service wasn't bound")
                }
            }
        }
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
        unbindCustomTabsService()
        context = null
        channel = null
    }

    override fun onMethodCall(call: MethodCall, resultCallback: Result) {
        when (call.method) {
            "authenticate" -> {
                this.resultCallback = resultCallback
                url = Uri.parse(call.argument("url"))
                callbackUrlScheme = call.argument<String>("callbackUrlScheme")!!
                options = call.argument<Map<String, Any>>("options")!!

                println("Binding custom tabs service")

                connection?.let {
                    CustomTabsClient.bindCustomTabsService(
                        context!!,
                        CustomTabsClient.getPackageName(context!!, null) ?: "com.android.chrome",
                        it
                    )
                } ?: println("Connection is null")
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

    private fun launchCustomTabs() {
        println("Creating custom tabs intent")
        callbacks[callbackUrlScheme!!] = resultCallback!!
        // Clear session cookies and history
        val headers = Bundle().apply {
            putString("Clear-Data", "true")
        }
        val builder = CustomTabsIntent.Builder(customTabsSession)
        // Create and configure the intent
        val customTabsIntent = builder.build().apply {
            intent.putExtra(Browser.EXTRA_HEADERS, headers)

            // Optional: Add flags to prevent history
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        }


        // Clear data when closing
        customTabsIntent.intent.putExtra("android.intent.extra.CLEAR_WHEN_TASK_RESET", true)

        // Launch with clean session
        customTabsIntent.launchUrl(context!!, url!!)
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
        @Suppress("UNCHECKED_CAST") val customTabsPackageOrder =
            (options["customTabsPackageOrder"] as Iterable<String>?) ?: emptyList()
        // Check target browser
        var targetPackage = customTabsPackageOrder.firstOrNull { isSupportCustomTabs(it) }
        if (targetPackage != null) {
            return targetPackage
        }

        // Check default browser
        val defaultBrowserSupported =
            CustomTabsClient.getPackageName(context!!, emptyList<String>()) != null
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

        val allBrowser =
            viewIntentHandlers.map { it.activityInfo.packageName }.sortedWith(compareBy {
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
            context!!, arrayListOf(packageName), true
        )
        return value == packageName
    }

}
