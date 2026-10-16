package com.winlator.cmod.runtime.compat.ue

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ue5IniTest {
    private val cvars = linkedMapOf("r.Lumen.TraceMeshSDFs" to "0", "r.ScreenPercentage" to "75")

    @Test fun addsSectionToEmptyFile() {
        val out = Ue5Ini.applyManagedBlock("", cvars)
        assertEquals(
            "[SystemSettings]\n${Ue5Ini.BEGIN_MARKER}\nr.Lumen.TraceMeshSDFs=0\nr.ScreenPercentage=75\n${Ue5Ini.END_MARKER}\n",
            out,
        )
        assertEquals("", Ue5Ini.removeManagedBlock(out))
    }

    @Test fun appendsAtEndOfExistingSectionAndKeepsOtherContent() {
        val original = "[Core.System]\r\nPaths=../../Content\r\n\r\n[SystemSettings]\r\nr.Foo=1\r\n\r\n[/Script/Engine.Engine]\r\nbSmoothFrameRate=False\r\n"
        val out = Ue5Ini.applyManagedBlock(original, cvars)
        val expected = "[Core.System]\r\nPaths=../../Content\r\n\r\n[SystemSettings]\r\nr.Foo=1\r\n" +
            "${Ue5Ini.BEGIN_MARKER}\r\nr.Lumen.TraceMeshSDFs=0\r\nr.ScreenPercentage=75\r\n${Ue5Ini.END_MARKER}\r\n" +
            "\r\n[/Script/Engine.Engine]\r\nbSmoothFrameRate=False\r\n"
        assertEquals(expected, out)
        assertEquals(original, Ue5Ini.removeManagedBlock(out))
    }

    @Test fun reapplyReplacesBlockInsteadOfDuplicating() {
        val once = Ue5Ini.applyManagedBlock("[SystemSettings]\nr.Foo=1\n", cvars)
        val twice = Ue5Ini.applyManagedBlock(once, mapOf("r.Nanite" to "0"))
        assertEquals(1, Regex(Regex.escape(Ue5Ini.BEGIN_MARKER)).findAll(twice).count())
        assertTrue(twice.contains("r.Nanite=0"))
        assertFalse(twice.contains("r.ScreenPercentage"))
    }

    @Test fun keepsUserSectionWhenRemoving() {
        val original = "[SystemSettings]\nr.Foo=1\n"
        assertEquals(original, Ue5Ini.removeManagedBlock(Ue5Ini.applyManagedBlock(original, cvars)))
    }

    @Test fun preservesUtf16Encoding() {
        val dir = Files.createTempDirectory("ue5ini").toFile()
        val ini = File(dir, "Engine.ini")
        val text = "[SystemSettings]\r\nr.Foo=1\r\n"
        ini.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE))

        assertTrue(Ue5Ini.applyToFile(ini, cvars))
        val decoded = Ue5Ini.decode(ini.readBytes())
        assertEquals(Charsets.UTF_16LE, decoded.charset)
        assertTrue(decoded.text.contains("r.ScreenPercentage=75"))
        assertTrue(File(dir, "Engine.ini" + Ue5Ini.BACKUP_SUFFIX).exists())

        assertFalse(Ue5Ini.applyToFile(ini, cvars)) // already up to date
        assertTrue(Ue5Ini.applyToFile(ini, emptyMap()))
        assertEquals(text, Ue5Ini.decode(ini.readBytes()).text)
        dir.deleteRecursively()
    }

    @Test fun removesOurValuesWhenGameDroppedTheMarkers() {
        // UE rewrote the file: comments gone, our lines left behind, plus the game's own.
        val rewritten = "[SystemSettings]\nr.Foo=1\nr.Lumen.TraceMeshSDFs=0\nr.ScreenPercentage=75\n"
        assertEquals("[SystemSettings]\nr.Foo=1\n", Ue5Ini.removeManagedBlock(rewritten, cvars))
        // A value the game changed afterwards is the game's, not ours: keep it.
        val changedByGame = "[SystemSettings]\nr.ScreenPercentage=100\n"
        assertEquals(changedByGame, Ue5Ini.removeManagedBlock(changedByGame, cvars))
        // Re-applying does not duplicate keys.
        val reapplied = Ue5Ini.applyManagedBlock(rewritten, cvars, cvars)
        assertEquals(1, Regex("r\\.ScreenPercentage=").findAll(reapplied).count())
    }

    @Test fun neverRemovesValuesTheOriginalFileHad() {
        val dir = Files.createTempDirectory("ue5orig").toFile()
        val ini = File(dir, "Engine.ini")
        ini.writeText("[SystemSettings]\nr.ScreenPercentage=75\n")
        Ue5Ini.applyToFile(ini, cvars)
        // Simulate the game stripping our markers, then turn the tweaks off.
        ini.writeText("[SystemSettings]\nr.ScreenPercentage=75\nr.Lumen.TraceMeshSDFs=0\n")
        Ue5Ini.applyToFile(ini, emptyMap(), cvars)
        assertEquals("[SystemSettings]\nr.ScreenPercentage=75\n", ini.readText())
        dir.deleteRecursively()
    }

    @Test fun readOnlyLockIsAppliedAndReleased() {
        val dir = Files.createTempDirectory("ue5ro").toFile()
        val ini = File(dir, "Engine.ini")
        ini.writeText("[SystemSettings]\nr.Foo=1\n")

        assertTrue(Ue5Ini.applyToFile(ini, cvars, emptyMap(), readOnly = true))
        assertFalse(Ue5Ini.isOwnerWritable(ini))
        assertFalse(Ue5Ini.applyToFile(ini, cvars, cvars, readOnly = true)) // nothing to do

        // Changing the preset while locked still works.
        assertTrue(Ue5Ini.applyToFile(ini, mapOf("r.Nanite" to "0"), cvars, readOnly = true))
        assertTrue(ini.readText().contains("r.Nanite=0"))
        assertFalse(Ue5Ini.isOwnerWritable(ini))

        // Unticking the lock, then turning the tweaks off, restores a normal file.
        assertTrue(Ue5Ini.applyToFile(ini, mapOf("r.Nanite" to "0"), mapOf("r.Nanite" to "0"), readOnly = false))
        assertTrue(Ue5Ini.isOwnerWritable(ini))
        Ue5Ini.applyToFile(ini, emptyMap(), mapOf("r.Nanite" to "0"), readOnly = true)
        assertTrue(Ue5Ini.isOwnerWritable(ini))
        assertEquals("[SystemSettings]\nr.Foo=1\n", ini.readText())
        dir.deleteRecursively()
    }

    @Test fun detectsProjectFromWin64AndFromRoot() {
        val root = Files.createTempDirectory("game").toFile()
        val win64 = File(root, "Stalker2/Binaries/Win64").apply { mkdirs() }
        File(win64, "Stalker2-Win64-Shipping.exe").writeText("")
        File(root, "Engine/Binaries/Win64").mkdirs()

        assertEquals("Stalker2", Ue5Ini.detectProjectName(win64))
        assertEquals("Stalker2", Ue5Ini.detectProjectName(root))
        assertNull(Ue5Ini.detectProjectName(Files.createTempDirectory("notue").toFile()))
        root.deleteRecursively()
    }

    @Test fun configDirPrefersExistingFolders() {
        val user = Files.createTempDirectory("user").toFile()
        assertEquals(
            listOf(File(user, "AppData/Local/Game/Saved/Config/Windows")),
            Ue5Ini.configDirCandidates(user, "Game"),
        )
        val noEditor = File(user, "AppData/Local/game/Saved/Config/WindowsNoEditor").apply { mkdirs() }
        assertEquals(listOf(noEditor), Ue5Ini.configDirCandidates(user, "Game"))
        user.deleteRecursively()
    }

    @Test fun presetsAndCustomCvars() {
        assertEquals(Ue5Preset.BALANCED, Ue5Preset.fromId("nonsense"))
        assertEquals(Ue5Preset.NO_NANITE, Ue5Preset.fromId("NO_NANITE"))
        assertEquals("8", Ue5Preset.PERFORMANCE.cvars["r.Nanite.MaxPixelsPerEdge"])
        val custom = Ue5Preset.parseCustom("r.Foo=1, r.Bar 2;bad name=1\nr.Quote=\"x\",r.Empty=")
        assertEquals(mapOf("r.Foo" to "1", "r.Bar" to "2"), custom)
        assertEquals("50", Ue5Preset.resolve(Ue5Preset.PERFORMANCE, "r.ScreenPercentage=50")["r.ScreenPercentage"])
    }
}
