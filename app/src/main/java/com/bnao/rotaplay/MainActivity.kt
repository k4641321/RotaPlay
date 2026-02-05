package com.bnao.rotaplay

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private lateinit var myWebView: WebView
    private lateinit var sensorHandler: SensorHandler
    private lateinit var webViewHandler: WebViewHandler
    private lateinit var fileHelper: FileHelper
    private lateinit var vibratorHelper: VibratorHelper
    private lateinit var chartToolHelper: ChartToolHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )
        ActivityCompat.requestPermissions(this, permissions, 1)

        sensorHandler = SensorHandler(this)
        webViewHandler = WebViewHandler(this)
        fileHelper = FileHelper(this)
        vibratorHelper = VibratorHelper(this)
        chartToolHelper = ChartToolHelper()

        myWebView = findViewById(R.id.webview)
        webViewHandler.setupWebView(myWebView)
        myWebView.loadUrl("file:///android_asset/index.html")

        myWebView.addJavascriptInterface(sensorHandler, "Androidsensor")
        myWebView.addJavascriptInterface(fileHelper, "Androidfile")
        myWebView.addJavascriptInterface(vibratorHelper, "Androidvibrator")
        myWebView.addJavascriptInterface(chartToolHelper, "Androidcharttool")
    }

    override fun onResume() {
        super.onResume()
        sensorHandler.registerSensorListener()
    }

    override fun onPause() {
        super.onPause()
        sensorHandler.unregisterSensorListener()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        webViewHandler.handleActivityResult(requestCode, resultCode, data)
    }
}