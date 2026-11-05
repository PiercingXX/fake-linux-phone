package com.piercingxx.linux

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.app.role.RoleManager
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.piercingxx.linux.service.FakeLinuxService
import com.piercingxx.linux.runtime.LinuxRuntime
import com.piercingxx.linux.runtime.AndroidAppBridge
import com.piercingxx.linux.runtime.AppUpdater
import com.piercingxx.linux.runtime.DesktopIntegration
import com.piercingxx.linux.view.AndroidSurfaceViewFactory
import com.piercingxx.linux.x11.X11ServerService
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.fakelinux/core"
        private const val TAG = "MainActivity"
        /** Sent by the "Open" notification action: show the desktop, resuming a running session. */
        const val ACTION_OPEN_DESKTOP = "com.piercingxx.linux.action.OPEN_DESKTOP"
        private val packageOperationRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /** Icon lookups may download from the network; cap concurrency so a long list doesn't time out. */
    private val linuxIconExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var desktopIntegration: DesktopIntegration

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logBuffer = StringBuffer()
    private var lastLogFlushTime = 0L
    private val logFlushRunnable = Runnable { flushLogBuffer() }

    private fun flushLogBuffer() {
        val text = synchronized(logBuffer) {
            val t = logBuffer.toString()
            logBuffer.setLength(0)
            t
        }
        if (text.isNotEmpty()) {
            flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to text))
            }
        }
    }

    private fun appendTerminalOutput(chunk: String) {
        synchronized(logBuffer) {
            logBuffer.append(chunk)
            val now = System.currentTimeMillis()
            if (now - lastLogFlushTime > 150) {
                lastLogFlushTime = now
                mainHandler.post(logFlushRunnable)
            } else {
                mainHandler.removeCallbacks(logFlushRunnable)
                mainHandler.postDelayed(logFlushRunnable, 150)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        linuxRuntime = LinuxRuntime(this)
        desktopIntegration = DesktopIntegration(this)

        handleHomeLaunch(intent)
        maybeAutoStartDesktop(intent)
    }

    /** Optional "Auto-Start Linux Desktop" setting: open the desktop whenever the app starts. */
    private fun maybeAutoStartDesktop(launchIntent: Intent) {
        // Home presses and the notification's Open action already launch the desktop.
        val isHomeLaunch = launchIntent.action == Intent.ACTION_MAIN && launchIntent.hasCategory(Intent.CATEGORY_HOME)
        if (isHomeLaunch || launchIntent.action == ACTION_OPEN_DESKTOP) return
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("flutter.autoStartDesktop", false)
        if (autoStart && linuxRuntime.isBootstrapped() && linuxRuntime.getInstalledDE().isNotEmpty()) {
            startForegroundService()
            val desktopIntent = Intent(this, com.piercingxx.linux.view.DesktopActivity::class.java).apply {
                putExtra("startSession", !linuxRuntime.isRunning())
                putExtra("mode", "termux")
                putExtra("de", linuxRuntime.getInstalledDE())
            }
            startActivity(desktopIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        restoreSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restoreSystemBars()
            window.decorView.post { restoreSystemBars() }
        }
    }

    @Suppress("DEPRECATION")
    /**
     * Undo the desktop's immersive mode while keeping the app edge-to-edge: Flutter draws behind
     * the transparent bars and pads with SafeArea. Fitting system windows here would leave the
     * bare (black) window showing through the status bar.
     */
    private fun restoreSystemBars() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        }
        // Resetting the flags above clears the icon colour; re-apply the app theme's choice.
        lightSystemBars?.let(::updateStatusBarIcons)
    }

    /** Last status-bar icon mode requested by Flutter; null until the theme reports it. */
    private var lightSystemBars: Boolean? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeLaunch(intent)
    }

    private fun handleHomeLaunch(homeIntent: Intent) {
        val isHomeLaunch = homeIntent.action == Intent.ACTION_MAIN && homeIntent.hasCategory(Intent.CATEGORY_HOME)
        if (!isHomeLaunch && homeIntent.action != ACTION_OPEN_DESKTOP) return
        if (homeIntent.action == ACTION_OPEN_DESKTOP && com.piercingxx.linux.view.DesktopActivity.isOpen()) {
            // Reuse the existing desktop screen; a second one would fight over the shared LorieView.
            startActivity(
                Intent(this, com.piercingxx.linux.view.DesktopActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            if (!isTaskRoot) finish()
            return
        }

        thread(name = "home-launch") {
            val desktopEnv = linuxRuntime.getInstalledDE()
            val ready = linuxRuntime.isBootstrapped() && desktopEnv.isNotEmpty()
            if (!ready) {
                Log.i(TAG, "Home launch opened setup because Linux is not ready")
                return@thread
            }

            startForegroundService()
            val sessionRunning = linuxRuntime.isRunning()
            runOnUiThread {
                startActivity(Intent(this, com.piercingxx.linux.view.DesktopActivity::class.java).apply {
                    putExtra("startSession", !sessionRunning)
                    putExtra("mode", "termux")
                    putExtra("de", desktopEnv)
                })
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("fakelinux-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    result.success(mapOf(
                        "isBootstrapped" to linuxRuntime.isBootstrapped(),
                        "isRunning" to linuxRuntime.isRunning(),
                        "distro" to "termux-native",
                        "installedDE" to linuxRuntime.getInstalledDE(),
                    ))
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "graphicsMode" to linuxRuntime.getGraphicsMode(),
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                // ── Native Termux desktop install ──
                "installDesktopNative" -> {
                    val desktopEnv = call.argument<String>("de") ?: "i3"
                    installThread("install-desktop-native") {
                        linuxRuntime.setInstallLogSink { chunk ->
                            appendTerminalOutput(chunk)
                        }
                        try {
                            val ok = linuxRuntime.installDesktopEnvironmentNative(
                                desktopEnv,
                            ) { progress, status ->
                                runOnUiThread {
                                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                                }
                            }
                            runOnUiThread { result.success(ok) }
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                    }
                }

                "getOptionalApps" -> {
                    result.success(linuxRuntime.getOptionalAppsStatus())
                }

                "installOptionalApp" -> {
                    val appId = call.argument<String>("appId") ?: ""
                    installThread("install-optional-app") {
                        val logSink: (String) -> Unit = { chunk ->
                            appendTerminalOutput(chunk)
                        }
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod(
                                        "onOptionalInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                            }
                        }

                        linuxRuntime.setInstallLogSink(logSink)
                        val ok = try {
                            linuxRuntime.installOptionalApp(appId, progressSink)
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                // ── Native package store ──
                "searchNativePackages" -> {
                    val query = call.argument<String>("query") ?: ""
                    val limit = call.argument<Int>("limit") ?: 60
                    thread(name = "package-search") {
                        val packages = linuxRuntime.searchNativePackages(query, limit)
                        runOnUiThread { result.success(packages) }
                    }
                }

                "getInstalledNativePackages" -> {
                    thread(name = "installed-packages") {
                        val packages = linuxRuntime.getInstalledNativePackages()
                        runOnUiThread { result.success(packages) }
                    }
                }

                "installNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = false)
                }

                "getUpgradablePackages" -> {
                    val refresh = call.argument<Boolean>("refresh") ?: false
                    thread(name = "upgradable-packages") {
                        val packages = runCatching { linuxRuntime.getUpgradablePackages(refresh) }
                            .getOrDefault(emptyList())
                        runOnUiThread { result.success(packages) }
                    }
                }

                "upgradeAllPackages" -> runPackageOperation(
                    flutterEngine, result, "updates", remove = false,
                ) { progress -> linuxRuntime.upgradeAllPackages(progress) }

                "getStorageInfo" -> {
                    thread(name = "storage-info") {
                        val info = runCatching { linuxRuntime.getStorageInfo() }
                            .getOrDefault(emptyMap())
                        runOnUiThread { result.success(info) }
                    }
                }

                "cleanPackageCache" -> {
                    thread(name = "clean-package-cache") {
                        val freed = runCatching { linuxRuntime.cleanPackageCache() }.getOrDefault(-1L)
                        runOnUiThread { result.success(freed) }
                    }
                }

                "removeNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = true)
                }

                "cancelNativePackageOperation" -> {
                    result.success(linuxRuntime.cancelPackageOperation())
                }

                // ── Android + Linux desktop integration ──
                "searchDesktopItems" -> {
                    val query = call.argument<String>("query") ?: ""
                    thread(name = "desktop-search") {
                        val items = desktopIntegration.search(query)
                        runOnUiThread { result.success(items) }
                    }
                }

                "launchDesktopItem" -> {
                    val kind = call.argument<String>("kind") ?: ""
                    val id = call.argument<String>("id") ?: ""
                    when (kind) {
                        "android" -> result.success(AndroidAppBridge.launchAndroidPackage(this, id))
                        "setting" -> {
                            AndroidAppBridge.launchSystemAction(this, id)
                            result.success(true)
                        }
                        "linux", "folder" -> {
                            thread(name = "launch-desktop-item") {
                                val running = linuxRuntime.isRunning()
                                if (!running) {
                                    runOnUiThread { openDesktop(startSession = true) }
                                    linuxRuntime.waitForDesktopReady(linuxRuntime.getInstalledDE())
                                }
                                val safeId = id.takeIf { it.matches(Regex("[A-Za-z0-9_.+-]+")) }
                                val command = if (kind == "linux" && safeId != null) {
                                    "DISPLAY=:0 gtk-launch '$safeId' >/dev/null 2>&1 &"
                                } else {
                                    val folder = when (id) {
                                        "Downloads", "Documents", "Pictures" -> id
                                        else -> ""
                                    }
                                    "DISPLAY=:0 kitty yazi \"${filesDir.absolutePath}/home${if (folder.isEmpty()) "" else "/$folder"}\" >/dev/null 2>&1 &"
                                }
                                linuxRuntime.executeCommand(command)
                                runOnUiThread {
                                    if (running) openDesktop(startSession = false)
                                    result.success(true)
                                }
                            }
                        }
                        else -> result.success(false)
                    }
                }

                // ── In-app updates ──
                "getAppVersion" -> result.success(AppUpdater.currentVersion(this))
                "canInstallUpdates" -> result.success(AppUpdater.canInstall(this))
                "openInstallPermission" -> {
                    runCatching { AppUpdater.openInstallPermissionSettings(this) }
                    result.success(true)
                }
                "cancelUpdateDownload" -> {
                    AppUpdater.cancel()
                    result.success(true)
                }
                "cleanupUpdates" -> {
                    thread(name = "update-cleanup") { runCatching { AppUpdater.cleanup(this) } }
                    result.success(true)
                }
                "downloadUpdate" -> {
                    val url = call.argument<String>("url").orEmpty()
                    val size = (call.argument<Number>("size") ?: 0).toLong()
                    val tag = call.argument<String>("tag") ?: "latest"
                    installThread("update-download") {
                        val messenger = flutterEngine.dartExecutor.binaryMessenger
                        val outcome: Map<String, Any?> = try {
                            val apk = AppUpdater.download(this, url, size, tag) { done, total ->
                                runOnUiThread {
                                    MethodChannel(messenger, CHANNEL).invokeMethod(
                                        "onUpdateProgress",
                                        mapOf("downloaded" to done, "total" to total),
                                    )
                                }
                            }
                            mapOf("ok" to true, "path" to apk.absolutePath)
                        } catch (error: AppUpdater.UpdateException) {
                            mapOf("ok" to false, "code" to error.code, "message" to error.message)
                        } catch (error: Throwable) {
                            Log.e(TAG, "Update download failed", error)
                            mapOf("ok" to false, "code" to "unknown", "message" to error.message)
                        }
                        runOnUiThread { result.success(outcome) }
                    }
                }
                "installUpdate" -> {
                    val path = call.argument<String>("path").orEmpty()
                    val ok = runCatching { AppUpdater.install(this, java.io.File(path)) }
                        .onFailure { Log.e(TAG, "Could not open the installer", it) }
                        .isSuccess
                    result.success(ok)
                }

                "getAndroidApps" -> result.success(AndroidAppBridge.listApps(this))
                "getLinuxAppIcon" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    val iconNames = call.argument<List<String>>("iconNames").orEmpty()
                    linuxIconExecutor.execute {
                        val prefix = java.io.File(filesDir, "usr").takeIf { it.isDirectory }
                        val icon = runCatching {
                            com.piercingxx.linux.runtime.LinuxAppIcons.find(
                                this@MainActivity, prefix, packageName, iconNames,
                            )
                        }.getOrNull()
                        runOnUiThread { result.success(icon) }
                    }
                }
                "getAppIcon" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    thread(name = "app-icon") {
                        val png = runCatching {
                            val size = 96
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                size, size, android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            packageManager.getApplicationIcon(packageName).apply {
                                setBounds(0, 0, size, size)
                                draw(android.graphics.Canvas(bitmap))
                            }
                            java.io.ByteArrayOutputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        }.getOrNull()
                        runOnUiThread { result.success(png) }
                    }
                }
                "getDockPackages" -> result.success(AndroidAppBridge.getDockPackages(this))
                "saveDockPackages" -> {
                    val packages = call.argument<List<String>>("packages").orEmpty()
                    AndroidAppBridge.setDockPackages(this, packages)
                    thread(name = "sync-desktop-dock") {
                        val synced = runCatching {
                            syncAndroidDesktopIntegration()
                            true
                        }.onFailure { Log.e(TAG, "Could not refresh desktop dock", it) }
                            .getOrDefault(false)
                        runOnUiThread { result.success(synced) }
                    }
                }

                "listDesktopSnapshots" -> result.success(desktopIntegration.listSnapshots())
                "createDesktopSnapshot" -> thread(name = "create-desktop-snapshot") {
                    runCatching { desktopIntegration.createSnapshot() }
                        .onSuccess { value -> runOnUiThread { result.success(value) } }
                        .onFailure { error -> runOnUiThread { result.error("snapshot", error.message, null) } }
                }
                "restoreDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    thread(name = "restore-desktop-snapshot") {
                        runCatching {
                            if (linuxRuntime.isRunning()) linuxRuntime.stopSession()
                            val savedPackages = desktopIntegration.restoreSnapshot(name)
                            val missingPackages = desktopIntegration.missingPackages(savedPackages)
                            if (missingPackages.isNotEmpty()) {
                                val command = "DEBIAN_FRONTEND=noninteractive apt-get " +
                                    "-o Dpkg::Options::=--force-confdef " +
                                    "-o Dpkg::Options::=--force-confold --no-upgrade install -y " +
                                    missingPackages.joinToString(" ")
                                linuxRuntime.executeCommand(command)
                            }
                            true
                        }.onSuccess { value -> runOnUiThread { result.success(value) } }
                            .onFailure { error -> runOnUiThread { result.error("restore", error.message, null) } }
                    }
                }
                "deleteDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    result.success(runCatching { desktopIntegration.deleteSnapshot(name) }.getOrDefault(false))
                }
                "openAndroidControl" -> {
                    AndroidAppBridge.launchSystemAction(this, call.argument<String>("action") ?: "settings")
                    result.success(true)
                }

                // ── Start Linux session ──
                "startLinux" -> {
                    val desktopEnv = call.argument<String>("de") ?: "i3"
                    val mode = call.argument<String>("mode") ?: "x11"
                    var width = call.argument<Int>("width") ?: 1920
                    var height = call.argument<Int>("height") ?: 1080

                    if (height > 1080) {
                        val scale = 1080.0 / height
                        width = (width * scale).toInt()
                        height = 1080
                    }

                    startForegroundService()

                    installThread("start-linux-native") {
                        linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                        val installed = linuxRuntime.getInstalledDE()
                        val ready = installed == desktopEnv ||
                            linuxRuntime.installDesktopEnvironmentNative(desktopEnv)
                        if (!ready) {
                            Log.e(TAG, "Native Termux desktop setup failed; session was not launched")
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Native Linux setup failed. Check the setup log.",
                                    Toast.LENGTH_LONG,
                                ).show()
                                result.success(false)
                            }
                            return@installThread
                        }
                        runOnUiThread {
                            val intent = Intent(this@MainActivity, com.piercingxx.linux.view.DesktopActivity::class.java).apply {
                                putExtra("startSession", !linuxRuntime.isRunning())
                                putExtra("mode", "termux")
                                putExtra("de", desktopEnv)
                            }
                            startActivity(intent)
                            result.success(true)
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    // Flutter's "running" state can be stale (e.g. after Stop from the notification).
                    val running = linuxRuntime.isRunning()
                    if (!running) startForegroundService()
                    val intent = Intent(this@MainActivity, com.piercingxx.linux.view.DesktopActivity::class.java).apply {
                        putExtra("startSession", !running)
                        putExtra("mode", "termux")
                        putExtra("de", linuxRuntime.getInstalledDE())
                    }
                    startActivity(intent)
                    result.success(true)
                }

                "stopLinux" -> {
                    // A desktop screen left in the back stack would reopen onto a dead session.
                    com.piercingxx.linux.view.DesktopActivity.finishIfOpen()
                    thread(name = "stop-linux-session") {
                        linuxRuntime.stopSession()
                        stopService(Intent(this@MainActivity, X11ServerService::class.java))
                        stopForegroundService()
                        runOnUiThread { result.success(true) }
                    }
                }

                // ── Command execution ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    Thread {
                        val output = linuxRuntime.executeCommand(command) { chunk ->
                            appendTerminalOutput(chunk)
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "requestDefaultLauncher" -> {
                    requestDefaultLauncher()
                    result.success(true)
                }

                "unsetDefaultLauncher" -> {
                    thread(name = "unset-home-role") {
                        val removed = unsetDefaultLauncher()
                        runOnUiThread {
                            if (removed) openHomeChooser() else openHomeSettings()
                            result.success(removed)
                        }
                    }
                }

                "isDefaultLauncher" -> {
                    result.success(isDefaultLauncher())
                }

                "updateStatusBarTheme" -> {
                    val isLight = call.argument<Boolean>("isLightMode") ?: false
                    runOnUiThread {
                        updateStatusBarIcons(isLight)
                    }
                    result.success(true)
                }

                "setupBootstrap" -> {
                    installThread("setup-bootstrap") {
                        linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                        linuxRuntime.setupBootstrap()
                        runOnUiThread { result.success(true) }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun updateStatusBarIcons(isLightMode: Boolean) {
        lightSystemBars = isLightMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (isLightMode) lightBars else 0,
                lightBars,
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (isLightMode) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun syncAndroidDesktopIntegration() {
        AndroidAppBridge.syncLaunchers(
            context = this,
            homeDir = java.io.File(filesDir, "home"),
            python = java.io.File(filesDir, "usr/bin/python3"),
        )
    }

    private fun openDesktop(startSession: Boolean) {
        startForegroundService()
        startActivity(Intent(this, com.piercingxx.linux.view.DesktopActivity::class.java).apply {
            putExtra("startSession", startSession)
            putExtra("mode", "termux")
            putExtra("de", linuxRuntime.getInstalledDE())
        })
    }

    private fun runPackageOperation(
        flutterEngine: FlutterEngine,
        result: MethodChannel.Result,
        packageName: String,
        remove: Boolean,
        operation: ((progress: (Double, String) -> Unit) -> Boolean)? = null,
    ) {
        if (!packageOperationRunning.compareAndSet(false, true)) {
            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                "onPackageOperationProgress",
                mapOf(
                    "progress" to -1.0,
                    "status" to "Previous package operation is still stopping. Try again.",
                ),
            )
            result.success(false)
            return
        }
        installThread(if (remove) "package-remove" else "package-install") {
            linuxRuntime.beginPackageOperation()
            linuxRuntime.setInstallLogSink { chunk ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationLog",
                        mapOf("text" to chunk),
                    )
                }
            }
            val progress: (Double, String) -> Unit = { value, status ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationProgress",
                        mapOf("progress" to value, "status" to status),
                    )
                }
            }
            try {
                startForegroundService()
                val ok = if (operation != null) {
                    operation(progress)
                } else if (remove) {
                    linuxRuntime.removeStorePackage(packageName, progress)
                } else {
                    linuxRuntime.installStorePackage(packageName, progress)
                }
                runOnUiThread { result.success(ok) }
            } catch (error: Throwable) {
                Log.e(TAG, "Package operation failed for $packageName", error)
                progress(-1.0, error.message ?: "Package operation failed")
                runOnUiThread { result.success(false) }
            } finally {
                linuxRuntime.setInstallLogSink(null)
                linuxRuntime.finishPackageOperation()
                packageOperationRunning.set(false)
            }
        }
    }

    // ── Install wake lock ──

    private val installOperations = java.util.concurrent.atomic.AtomicInteger(0)
    private val installWakeLock by lazy {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FakeLinux::Install")
            .apply { setReferenceCounted(false) }
    }

    /**
     * Runs a long setup/install step on a background thread while keeping the CPU awake and the
     * screen on, so downloads and package installs are not paused when the phone would sleep.
     */
    private fun installThread(name: String, block: () -> Unit) = thread(name = name) {
        if (installOperations.getAndIncrement() == 0) {
            installWakeLock.acquire(2 * 60 * 60 * 1000L)
            runOnUiThread { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        try {
            block()
        } finally {
            if (installOperations.decrementAndGet() == 0) {
                if (installWakeLock.isHeld) installWakeLock.release()
                runOnUiThread { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    // ── Foreground Service ──

    private fun startForegroundService() {
        val intent = Intent(this, FakeLinuxService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, FakeLinuxService::class.java)
        stopService(intent)
    }

    // ── Battery Optimization ──

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun isDefaultLauncher(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(
                homeIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName == packageName
        }
    }

    private fun requestDefaultLauncher() {
        if (isDefaultLauncher()) {
            Toast.makeText(this, "FakeLinux is already the default launcher", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun unsetDefaultLauncher(): Boolean {
        if (!isDefaultLauncher()) return true

        // Older Android versions may still store Home as a preferred activity
        // rather than a RoleManager holder. Apps may clear their own preference.
        runCatching { packageManager.clearPackagePreferredActivities(packageName) }
        return !isDefaultLauncher()
    }

    private fun openHomeChooser() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        startActivity(Intent.createChooser(homeIntent, "Choose your Home app"))
    }

    private fun openHomeSettings() {
        Toast.makeText(
            this,
            "Android requires you to select another default Home app",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // ── Hardware Detection ──

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
