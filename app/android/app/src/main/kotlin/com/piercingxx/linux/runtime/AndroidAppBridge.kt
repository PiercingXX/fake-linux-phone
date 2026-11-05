package com.piercingxx.linux.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Makes Android launcher apps available to the Linux desktop without granting
 * the Linux processes any extra Android permissions.  Launcher entries (rofi drun) connect to
 * this app-private Unix socket; the Android service then launches the chosen
 * activity through the normal Android API.
 */
object AndroidAppBridge {
    private const val TAG = "AndroidAppBridge"
    private const val SOCKET_NAME = "fakelinux.android-app-launcher"

    @Volatile private var server: LocalServerSocket? = null

    fun listApps(context: Context): List<Map<String, String>> = launcherActivities(context).map {
        mapOf(
            "label" to it.loadLabel(context.packageManager).toString(),
            "package" to it.activityInfo.packageName,
            "source" to "Android",
        )
    }

    fun launchAndroidPackage(context: Context, packageName: String): Boolean =
        launchPackage(context, packageName)

    fun getDockPackages(context: Context): List<String> {
        val preferences = context.getSharedPreferences("desktop_integration", Context.MODE_PRIVATE)
        if (preferences.contains("dock_packages")) {
            return preferences.getString("dock_packages", "").orEmpty()
                .split(',').filter(String::isNotBlank)
        }
        return defaultDockPackages(launcherActivities(context))
    }

    fun setDockPackages(context: Context, packages: List<String>) {
        val installed = launcherActivities(context).map { it.activityInfo.packageName }.toSet()
        val safe = packages.filter { it in installed }.distinct().take(8)
        context.getSharedPreferences("desktop_integration", Context.MODE_PRIVATE)
            .edit().putString("dock_packages", safe.joinToString(",")).commit()
    }

    fun start(context: Context) {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            try {
                val socket = LocalServerSocket(SOCKET_NAME)
                server = socket
                thread(name = "android-app-bridge", isDaemon = true) {
                    serve(context.applicationContext, socket)
                }
                Log.i(TAG, "Android app launcher bridge started")
            } catch (error: Exception) {
                Log.e(TAG, "Could not start Android app launcher bridge", error)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            runCatching { server?.close() }
            server = null
        }
    }

    fun syncLaunchers(context: Context, homeDir: File, python: File) {
        if (!python.canExecute()) {
            Log.w(TAG, "Python is unavailable; Android app launchers were not synced")
            return
        }
        val appsDir = File(homeDir, ".local/share/applications/fakelinux-android").apply { mkdirs() }
        appsDir.listFiles()?.forEach { it.delete() }
        val iconsDir = File(homeDir, ".local/share/icons/fakelinux-android").apply { mkdirs() }
        iconsDir.listFiles()?.forEach { it.delete() }

        val launcher = File(homeDir, ".local/bin/fakelinux-launch-android-app.py")
        fun sessionPath(file: File): String = file.absolutePath
        val pythonPath = sessionPath(python)
        val launcherPath = sessionPath(launcher)
        launcher.parentFile?.mkdirs()
        launcher.writeText(
            """
            #!$pythonPath
            import socket
            import sys

            if len(sys.argv) != 2:
                raise SystemExit("Expected an Android package name")
            client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            client.connect("\0$SOCKET_NAME")
            client.sendall((sys.argv[1] + "\n").encode())
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val activities = launcherActivities(context)

        activities.forEach { activity ->
            val packageName = activity.activityInfo.packageName
            val label = desktopEscape(activity.loadLabel(context.packageManager).toString())
            val safeName = packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val iconFile = File(iconsDir, "$safeName.png")
            val icon = if (writeIconPng(activity.loadIcon(context.packageManager), iconFile)) {
                sessionPath(iconFile)
            } else {
                "applications-other"
            }
            val filename = "$safeName.desktop"
            File(appsDir, filename).writeText(
                """
                [Desktop Entry]
                Version=1.0
                Type=Application
                Name=$label
                Comment=Open Android app: $packageName
                Exec=$pythonPath $launcherPath $packageName
                Icon=$icon
                Terminal=false
                Categories=Utility;
                StartupNotify=false
                """.trimIndent() + "\n",
            )
        }
        syncUtilityLaunchers(homeDir, pythonPath, launcherPath, sessionPath(homeDir))
        Log.i(TAG, "Synced ${activities.size} Android app launchers into ${appsDir.absolutePath}")
    }

    private fun syncUtilityLaunchers(
        homeDir: File,
        pythonPath: String,
        launcherPath: String,
        homePath: String,
    ) {
        val appsDir = File(homeDir, ".local/share/applications/fakelinux-tools").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val folders = listOf(
            Triple("home", "Home folder", homePath),
            Triple("downloads", "Downloads", "$homePath/Downloads"),
            Triple("documents", "Documents", "$homePath/Documents"),
            Triple("pictures", "Pictures", "$homePath/Pictures"),
        )
        folders.forEach { (id, label, path) ->
            File(homeDir, id.replaceFirstChar { it.uppercase() }).takeIf { id != "home" }?.mkdirs()
            File(appsDir, "fakelinux-folder-$id.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=$label
                Comment=Open $label in yazi
                Exec=kitty yazi ${desktopEscape(path)}
                Icon=folder
                Terminal=false
                Categories=Utility;FileManager;
                Keywords=files;folder;search;$id;
                """.trimIndent() + "\n",
            )
        }
        listOf(
            Triple("wifi", "Wi-Fi settings", "network-wireless"),
            Triple("bluetooth", "Bluetooth settings", "bluetooth"),
            Triple("display", "Display and brightness", "video-display"),
            Triple("sound", "Sound and volume", "audio-volume-high"),
            Triple("hotspot", "Mobile hotspot", "network-transmit-receive"),
            Triple("battery", "Battery saver", "battery"),
        ).forEach { (id, label, icon) ->
            File(appsDir, "fakelinux-setting-$id.desktop").writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=$label
                Comment=Open Android $label
                Exec=$pythonPath $launcherPath action:$id
                Icon=$icon
                Terminal=false
                Categories=Settings;System;
                Keywords=phone;android;control center;$id;
                """.trimIndent() + "\n",
            )
        }

    }

    private fun serve(context: Context, socket: LocalServerSocket) {
        while (server === socket) {
            var client: LocalSocket? = null
            try {
                client = socket.accept()
                val command = client.inputStream.bufferedReader().readLine()?.trim().orEmpty()
                if (command.startsWith("action:")) {
                    launchSystemAction(context, command.removePrefix("action:"))
                } else {
                    launchPackage(context, command)
                }
            } catch (error: Exception) {
                if (server === socket) Log.w(TAG, "Android app launcher request failed", error)
            } finally {
                runCatching { client?.close() }
            }
        }
    }

    private fun launchPackage(context: Context, packageName: String): Boolean {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }
            .onFailure { Log.w(TAG, "Could not launch Android package $packageName", it) }
            .getOrDefault(false)
    }

    fun launchSystemAction(context: Context, action: String) {
        val settingsAction = when (action) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "hotspot" -> "android.settings.TETHER_SETTINGS"
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        runCatching {
            context.startActivity(Intent(settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "Could not open Android setting $action", it) }
    }

    private fun launcherActivities(context: Context): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
    }

    private fun defaultDockPackages(
        activities: List<android.content.pm.ResolveInfo>,
    ): List<String> {
        val installed = activities.map { it.activityInfo.packageName }.toSet()
        // Keep the default dock short; more apps can be pinned from Desktop Tools.
        return listOf("com.android.chrome").filter { it in installed }
    }

    private fun desktopEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", " ")

    private fun writeIconPng(drawable: android.graphics.drawable.Drawable, output: File): Boolean {
        return runCatching {
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            bitmap.recycle()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not export Android app icon to ${output.name}", error)
            false
        }
    }
}
