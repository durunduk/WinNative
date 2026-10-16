package com.winlator.cmod.runtime.compat.ue

import android.util.Log
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File

/**
 * Per-game Unreal Engine 5 performance tweaks.
 *
 * When enabled for a shortcut, the selected preset (plus any custom cvars) is
 *  1. written as a managed block into the game's Engine.ini inside the Wine prefix, and
 *  2. optionally passed on the command line as -dpcvars=...
 * Turning the option off removes the managed block again, leaving the game's own
 * settings untouched. State files live in drive_c/WinNative/ue5-tweaks.
 */
object Ue5Tweaks {
    private const val TAG = "Ue5Tweaks"

    const val EXTRA_ENABLED = "ue5Tweaks"
    const val EXTRA_PRESET = "ue5Preset"
    const val EXTRA_PROJECT = "ue5Project"
    const val EXTRA_CUSTOM_CVARS = "ue5CustomCvars"
    const val EXTRA_USE_ARGS = "ue5UseArgs"

    private const val STATE_DIR = ".wine/drive_c/WinNative/ue5-tweaks"

    @JvmStatic
    fun isEnabled(shortcut: Shortcut?): Boolean =
        shortcut != null && shortcut.getExtra(EXTRA_ENABLED, "0") == "1"

    @JvmStatic
    fun resolveCvars(shortcut: Shortcut): Map<String, String> =
        Ue5Preset.resolve(
            Ue5Preset.fromId(shortcut.getExtra(EXTRA_PRESET, Ue5Preset.DEFAULT.id)),
            shortcut.getExtra(EXTRA_CUSTOM_CVARS, ""),
        )

    /**
     * Extra command-line arguments for the game exe (with a leading space), or "".
     * Uses -dpcvars, which shipping builds accept; values never contain commas
     * or quotes because [Ue5Preset.parseCustom] rejects them.
     */
    @JvmStatic
    fun commandLineArgs(shortcut: Shortcut?): String {
        if (!isEnabled(shortcut) || shortcut!!.getExtra(EXTRA_USE_ARGS, "1") != "1") return ""
        val cvars = resolveCvars(shortcut)
        if (cvars.isEmpty()) return ""
        return " -dpcvars=" + cvars.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /**
     * Applies or removes the Engine.ini block before launch. [gameDir] is the
     * directory the game starts in (exe folder or install root); it is used to
     * find the UE project name when the user has not set one.
     * Never throws: a failure here must not block the game from starting.
     */
    @JvmStatic
    fun applyForLaunch(container: Container?, shortcut: Shortcut?, gameDir: File?) {
        if (container == null || shortcut == null) return
        try {
            val stateDir = File(container.getRootDir(), STATE_DIR)
            val enabled = isEnabled(shortcut)
            if (!enabled && stateDir.listFiles().isNullOrEmpty()) return

            val project = shortcut.getExtra(EXTRA_PROJECT, "").trim().ifEmpty { null }
                ?: Ue5Ini.detectProjectName(gameDir)
            if (project == null) {
                if (enabled) writeLog(stateDir, "UE project not found from '$gameDir'. Set the project name manually.")
                return
            }

            val userDir = File(container.getRootDir(), ".wine/drive_c/users/" + ImageFs.USER)
            val marker = File(stateDir, sanitize(project) + ".applied")
            val cvars = if (enabled) resolveCvars(shortcut) else emptyMap()
            if (!enabled && !marker.exists()) return

            val changed = ArrayList<String>()
            for (dir in Ue5Ini.configDirCandidates(userDir, project)) {
                val ini = File(dir, "Engine.ini")
                if (Ue5Ini.applyToFile(ini, cvars)) changed += ini.path.removePrefix(container.getRootDir().path)
            }

            if (enabled) {
                stateDir.mkdirs()
                marker.writeText(project)
                writeLog(
                    stateDir,
                    "project=$project preset=${shortcut.getExtra(EXTRA_PRESET, Ue5Preset.DEFAULT.id)}\n" +
                        cvars.entries.joinToString("\n") { "${it.key}=${it.value}" } +
                        "\nupdated: " + (if (changed.isEmpty()) "(already up to date)" else changed.joinToString()),
                )
            } else {
                marker.delete()
                writeLog(stateDir, "project=$project tweaks removed from: " + changed.joinToString())
            }
            Log.i(TAG, "project=$project enabled=$enabled changed=$changed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply UE5 tweaks", e)
        }
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun writeLog(stateDir: File, text: String) {
        try {
            stateDir.mkdirs()
            File(stateDir, "last.log").writeText(text + "\n")
        } catch (_: Exception) {
        }
    }
}
