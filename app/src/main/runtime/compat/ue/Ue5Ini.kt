package com.winlator.cmod.runtime.compat.ue

import java.io.File
import java.nio.charset.Charset

/**
 * Pure helpers for Unreal Engine config files and game-folder layout.
 * No Android dependencies so they can be unit-tested on the JVM.
 */
object Ue5Ini {
    const val SECTION = "[SystemSettings]"
    const val BEGIN_MARKER = "; >>> WinNative UE5 tweaks (managed automatically, edits here are overwritten)"
    const val END_MARKER = "; <<< WinNative UE5 tweaks"
    const val BACKUP_SUFFIX = ".wnbak"

    /**
     * Writes [cvars] as a managed block at the end of [SystemSettings] (UE uses the
     * last assignment of a key, so values placed last win over the game's own).
     * Any previous managed block is replaced. Everything else is kept verbatim.
     *
     * [previous] are the cvars written last time. When the game rewrote the file
     * and dropped our comment markers, those lines are still recognised and removed.
     */
    @JvmStatic
    @JvmOverloads
    fun applyManagedBlock(
        original: String,
        cvars: Map<String, String>,
        previous: Map<String, String> = emptyMap(),
    ): String {
        val nl = detectNewline(original)
        val lines = splitLines(removeManagedBlock(original, previous))
        if (cvars.isEmpty()) return joinLines(lines, nl, original)

        val block = ArrayList<String>(cvars.size + 2)
        block += BEGIN_MARKER
        for ((k, v) in cvars) block += "$k=$v"
        block += END_MARKER

        val sectionIdx = lines.indexOfFirst { it.trim().equals(SECTION, ignoreCase = true) }
        val result = ArrayList<String>(lines.size + block.size + 2)
        if (sectionIdx < 0) {
            result += lines
            while (result.isNotEmpty() && result.last().isBlank()) result.removeAt(result.size - 1)
            if (result.isNotEmpty()) result += ""
            result += SECTION
            result += block
        } else {
            var end = sectionIdx + 1
            while (end < lines.size && !isSectionHeader(lines[end])) end++
            // Insert before the trailing blank lines that separate this section from the next.
            var insertAt = end
            while (insertAt > sectionIdx + 1 && lines[insertAt - 1].isBlank()) insertAt--
            result += lines.subList(0, insertAt)
            result += block
            result += lines.subList(insertAt, lines.size)
        }
        return joinLines(result, nl, original, forceTrailingNewline = true)
    }

    /**
     * Removes the managed block (if any) and nothing else. Lines in [SystemSettings]
     * that match [previous] exactly (our own values whose markers the game dropped
     * when it rewrote the file) are removed too.
     */
    @JvmStatic
    @JvmOverloads
    fun removeManagedBlock(original: String, previous: Map<String, String> = emptyMap()): String {
        if (!original.contains(BEGIN_MARKER) && previous.isEmpty()) return original
        val nl = detectNewline(original)
        val lines = splitLines(original)
        val prev = previous.entries.associate { it.key.lowercase() to it.value.trim() }
        val out = ArrayList<String>(lines.size)
        var inside = false
        var inSystemSettings = false
        for (line in lines) {
            val t = line.trim()
            if (isSectionHeader(line)) inSystemSettings = t.equals(SECTION, ignoreCase = true)
            when {
                t == BEGIN_MARKER -> inside = true
                t == END_MARKER -> inside = false
                inside -> {}
                inSystemSettings && isPreviousValue(t, prev) -> {}
                else -> out += line
            }
        }
        if (out.size == lines.size) return original
        // Drop a [SystemSettings] section that only existed because of us.
        val idx = out.indexOfFirst { it.trim().equals(SECTION, ignoreCase = true) }
        if (idx >= 0) {
            var end = idx + 1
            while (end < out.size && !isSectionHeader(out[end])) end++
            if (out.subList(idx + 1, end).all { it.isBlank() }) {
                out.subList(idx, end).clear()
                // If that section was last, drop the blank separator we left behind.
                if (idx == out.size) {
                    while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
                }
            }
        }
        return joinLines(out, nl, original)
    }

    @JvmStatic
    fun hasManagedBlock(text: String): Boolean = text.contains(BEGIN_MARKER)

    /** key=value pairs of the [SystemSettings] section(s), keys lower-cased. */
    @JvmStatic
    fun systemSettingsPairs(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var inSystemSettings = false
        for (line in splitLines(text)) {
            val t = line.trim()
            if (isSectionHeader(line)) {
                inSystemSettings = t.equals(SECTION, ignoreCase = true)
                continue
            }
            if (!inSystemSettings) continue
            val eq = t.indexOf('=')
            if (eq <= 0 || t.startsWith(";")) continue
            out[t.substring(0, eq).trim().lowercase()] = t.substring(eq + 1).trim()
        }
        return out
    }

    private fun isPreviousValue(trimmed: String, prev: Map<String, String>): Boolean {
        if (prev.isEmpty()) return false
        val eq = trimmed.indexOf('=')
        if (eq <= 0 || trimmed.startsWith(";")) return false
        val value = prev[trimmed.substring(0, eq).trim().lowercase()] ?: return false
        return trimmed.substring(eq + 1).trim() == value
    }

    // ---- file I/O preserving the original encoding (UE writes UTF-8 or UTF-16LE) ----

    class Decoded(val text: String, val charset: Charset, val bom: ByteArray)

    @JvmStatic
    fun decode(bytes: ByteArray): Decoded = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        else -> Decoded(String(bytes, Charsets.UTF_8), Charsets.UTF_8, ByteArray(0))
    }

    @JvmStatic
    fun encode(text: String, like: Decoded?): ByteArray {
        val cs = like?.charset ?: Charsets.UTF_8
        val bom = like?.bom ?: ByteArray(0)
        return bom + text.toByteArray(cs)
    }

    /**
     * Applies (or, with empty [cvars], removes) the managed block in [file].
     * A one-time backup of the untouched original is kept next to it.
     *
     * [previous] are the cvars written last time (see [removeManagedBlock]); values
     * the original file already had are never removed. With [readOnly] the file is
     * left without write permission so the game cannot rewrite it (Wine reports it
     * as read-only); otherwise write permission is restored.
     * Returns true if the file content or its permission changed.
     */
    @JvmStatic
    @JvmOverloads
    fun applyToFile(
        file: File,
        cvars: Map<String, String>,
        previous: Map<String, String> = emptyMap(),
        readOnly: Boolean = false,
    ): Boolean {
        val backup = File(file.path + BACKUP_SUFFIX)
        val existed = file.isFile
        val decoded = if (existed) decode(file.readBytes()) else null
        val before = decoded?.text ?: ""

        val original = if (backup.isFile) systemSettingsPairs(decode(backup.readBytes()).text) else emptyMap()
        val ours = previous.filter { (k, v) -> original[k.lowercase()] != v.trim() }
        val after = if (cvars.isEmpty()) removeManagedBlock(before, ours) else applyManagedBlock(before, cvars, ours)

        var changed = false
        if (after != before && (existed || cvars.isNotEmpty())) {
            if (existed) setOwnerWritable(file, true)
            // We created this file ourselves and nothing else is left in it: remove it.
            if (after.isBlank() && !backup.exists()) {
                file.delete()
                return true
            }
            if (existed && !backup.exists() && !hasManagedBlock(before)) file.copyTo(backup)
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".wntmp")
            tmp.writeBytes(encode(after, decoded))
            if (!tmp.renameTo(file)) {
                file.writeBytes(tmp.readBytes())
                tmp.delete()
            }
            changed = true
        }

        if (file.isFile) {
            val wantWritable = !(readOnly && cvars.isNotEmpty())
            if (isOwnerWritable(file) != wantWritable) {
                setOwnerWritable(file, wantWritable)
                changed = true
            }
        }
        return changed
    }

    /** Owner write bit; independent of who runs the check (root ignores permissions). */
    @JvmStatic
    fun isOwnerWritable(file: File): Boolean = try {
        java.nio.file.Files.getPosixFilePermissions(file.toPath())
            .contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
    } catch (e: Exception) {
        file.canWrite()
    }

    private fun setOwnerWritable(file: File, writable: Boolean) {
        try {
            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath()).toMutableSet()
            val w = listOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            )
            if (writable) perms += w[0] else perms.removeAll(w.toSet())
            java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (e: Exception) {
            file.setWritable(writable, false)
        }
    }

    // ---- game layout detection ----

    private const val SHIPPING_SUFFIX = "-Win64-Shipping.exe"

    /**
     * Finds the UE project name for a game, starting from the directory the game is
     * launched in (exe folder or install root). Recognises:
     *  - <Root>/<Project>/Binaries/Win64/<Project>-Win64-Shipping.exe (start in Win64 or Root)
     *  - a bootstrap <Game>.exe in <Root> next to an <Project>/Binaries/Win64 folder.
     */
    @JvmStatic
    fun detectProjectName(startDir: File?): String? {
        if (startDir == null) return null
        var dir: File? = startDir
        var depth = 0
        while (dir != null && depth < 4) {
            if (dir.name.equals("Win64", true) && dir.parentFile?.name.equals("Binaries", true)) {
                val project = dir.parentFile?.parentFile
                shippingExeProject(dir)?.let { return it }
                if (project != null && project.name.isNotEmpty() && !project.name.equals("Engine", true)) return project.name
            }
            scanForProject(dir)?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /** True if [dir] (or something near it) looks like an Unreal Engine game. */
    @JvmStatic
    fun looksLikeUnrealGame(startDir: File?): Boolean = detectProjectName(startDir) != null

    private fun shippingExeProject(win64: File): String? =
        win64.listFiles()
            ?.firstOrNull { it.isFile && it.name.endsWith(SHIPPING_SUFFIX, ignoreCase = true) }
            ?.name?.dropLast(SHIPPING_SUFFIX.length)
            ?.takeIf { it.isNotEmpty() }

    private fun scanForProject(root: File): String? {
        val children = root.listFiles() ?: return null
        for (child in children) {
            if (!child.isDirectory || child.name.equals("Engine", true)) continue
            val win64 = File(File(child, "Binaries"), "Win64")
            if (!win64.isDirectory) continue
            if (shippingExeProject(win64) != null || File(child, "Content").isDirectory) return child.name
        }
        return null
    }

    /**
     * Config directories a shipping build may read, relative to the prefix's user
     * folder ("drive_c/users/<user>"). The first entry is created if nothing exists.
     */
    @JvmStatic
    fun configDirCandidates(userDir: File, project: String): List<File> {
        val roots = listOf(
            File(userDir, "AppData/Local"),
            File(userDir, "Documents/My Games"),
            File(userDir, "My Documents/My Games"),
        )
        val out = LinkedHashSet<File>()
        for (root in roots) {
            val projectDir = findChildIgnoreCase(root, project) ?: continue
            val config = File(projectDir, "Saved/Config")
            for (platform in listOf("Windows", "WindowsNoEditor", "WinGDK")) {
                val d = findChildIgnoreCase(config, platform) ?: continue
                if (d.isDirectory) out += d
            }
        }
        if (out.isEmpty()) out += File(userDir, "AppData/Local/$project/Saved/Config/Windows")
        return out.toList()
    }

    private fun findChildIgnoreCase(parent: File, name: String): File? {
        val exact = File(parent, name)
        if (exact.exists()) return exact
        return parent.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    // ---- small text utilities ----

    private fun isSectionHeader(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("[") && t.endsWith("]")
    }

    private fun detectNewline(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

    private fun splitLines(text: String): MutableList<String> {
        if (text.isEmpty()) return ArrayList()
        val parts = text.split("\r\n", "\n").toMutableList()
        if (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        return parts
    }

    private fun joinLines(
        lines: List<String>,
        nl: String,
        original: String,
        forceTrailingNewline: Boolean = false,
    ): String {
        if (lines.isEmpty()) return ""
        val trailing = forceTrailingNewline || original.endsWith("\n")
        return lines.joinToString(nl) + if (trailing) nl else ""
    }
}
