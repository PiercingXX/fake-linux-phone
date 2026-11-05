package com.piercingxx.linux.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Unpacks the bundled PiercingXX rice (assets/fakelinux/dots) into the Linux home.
 *
 * Layout inside the asset tree:
 *   config/       -> ~/.config
 *   home/         -> ~
 *   scripts/      -> ~/.scripts
 *   backgrounds/  -> ~/Pictures/backgrounds
 *
 * Files under [MANAGED] belong to the app and are rewritten whenever [VERSION]
 * changes. Everything else is copied once and then left alone, so edits to
 * nvim, kitty, yazi and the shell survive app updates.
 * Android's asset packager drops dotfiles, so `dot_bashrc` is stored for `.bashrc`.
 * Text files get `@PREFIX@` and `@HOME@` replaced with the real paths, which is
 * how shebangs point at the app's private prefix instead of /usr/bin.
 */
object PiercingProfile {
    private const val TAG = "PiercingProfile"
    private const val VERSION = 1
    private const val ROOT = "fakelinux/dots"
    private const val MARKER = ".fakelinux-dots-version"

    /** Destination-relative prefixes that the app owns and may overwrite. */
    private val MANAGED = listOf(".config/i3/", ".config/i3status/", ".config/dunst/", ".scripts/")

    private val TARGETS = mapOf(
        "config" to ".config",
        "home" to "",
        "scripts" to ".scripts",
        "backgrounds" to "Pictures/backgrounds",
    )

    fun needsInstall(homeDir: File): Boolean {
        val marker = File(homeDir, MARKER)
        return !marker.exists() || marker.readText().trim() != VERSION.toString()
    }

    fun install(context: Context, homeDir: File, prefixDir: File): Boolean {
        val firstRun = !File(homeDir, MARKER).exists()
        val overwriteManaged = needsInstall(homeDir)
        if (!overwriteManaged) return true

        return try {
            val subs = mapOf(
                "@PREFIX@" to prefixDir.absolutePath,
                "@HOME@" to homeDir.absolutePath,
            )
            for ((assetDir, target) in TARGETS) {
                val dest = if (target.isEmpty()) homeDir else File(homeDir, target)
                copyTree(context, "$ROOT/$assetDir", dest, homeDir, subs, firstRun)
            }
            File(homeDir, "Pictures/Screenshots").mkdirs()
            File(homeDir, ".local/state/fakelinux").mkdirs()
            File(homeDir, MARKER).writeText("$VERSION\n")
            Log.i(TAG, "PiercingXX profile v$VERSION installed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Profile install failed: ${e.message}", e)
            false
        }
    }

    private fun copyTree(
        context: Context,
        assetPath: String,
        dest: File,
        homeDir: File,
        subs: Map<String, String>,
        firstRun: Boolean,
    ) {
        val children = context.assets.list(assetPath)?.toList().orEmpty()
        if (children.isEmpty()) {
            copyFile(context, assetPath, dest, homeDir, subs, firstRun)
            return
        }
        dest.mkdirs()
        for (name in children) {
            val realName = if (name.startsWith("dot_")) "." + name.removePrefix("dot_") else name
            copyTree(context, "$assetPath/$name", File(dest, realName), homeDir, subs, firstRun)
        }
    }

    private fun copyFile(
        context: Context,
        assetPath: String,
        dest: File,
        homeDir: File,
        subs: Map<String, String>,
        firstRun: Boolean,
    ) {
        val rel = dest.relativeTo(homeDir).path.replace(File.separatorChar, '/')
        val managed = MANAGED.any { rel.startsWith(it) }
        // Unmanaged files are written once. A version bump only touches managed ones.
        if (dest.exists() && !managed) return
        if (dest.exists() && managed && !firstRun) {
            // Keep one backup of whatever the user had before we replace it.
            val backup = File(dest.parentFile, dest.name + ".bak")
            if (!backup.exists()) dest.copyTo(backup)
        }
        dest.parentFile?.mkdirs()
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        if (isText(bytes)) {
            var text = String(bytes, Charsets.UTF_8)
            for ((token, value) in subs) text = text.replace(token, value)
            dest.writeText(text)
        } else {
            dest.writeBytes(bytes)
        }
        // Scripts and anything with a shebang must be runnable.
        if (rel.startsWith(".scripts/") || bytes.size > 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()) {
            dest.setExecutable(true, false)
        }
    }

    /** Treat a file as text when its first 4 KB contain no NUL byte. */
    private fun isText(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return false
        return true
    }
}
