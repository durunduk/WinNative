package com.winlator.cmod.runtime.compat.ue

/**
 * Console-variable presets for Unreal Engine 5 games.
 *
 * Every preset only uses engine cvars that a shipping build reads from
 * Engine.ini [SystemSettings] (and from -dpcvars). Cvars flagged as cheats are
 * deliberately avoided because shipping builds ignore them.
 *
 * Presets are ordered from "change as little as possible" to "change a lot".
 * Each later preset builds on the previous one so the behaviour is predictable.
 */
enum class Ue5Preset(val id: String, val cvars: Map<String, String>) {

    /**
     * Keeps Lumen and Nanite on but removes the parts that cost the most on Adreno:
     * per-mesh SDF tracing, async compute (Adreno has no real async queue) and
     * Virtual Shadow Maps.
     */
    LIGHT(
        "light",
        linkedMapOf(
            "r.Lumen.AsyncCompute" to "0",
            "r.Lumen.TraceMeshSDFs" to "0",
            "r.Lumen.HardwareRayTracing" to "0",
            "r.Shadow.Virtual.Enable" to "0",
            "r.Shadow.CSM.MaxCascades" to "2",
        )
    ),

    /** Lumen GI stays, but coarser; reflections fall back to SSR; Nanite is coarser. */
    BALANCED(
        "balanced",
        LIGHT.cvars + linkedMapOf(
            "r.Lumen.ScreenProbeGather.DownsampleFactor" to "32",
            "r.Lumen.ScreenProbeGather.TracingOctahedronResolution" to "4",
            "r.ReflectionMethod" to "2",
            "r.Nanite.MaxPixelsPerEdge" to "4",
            "r.MotionBlurQuality" to "0",
            "r.ScreenPercentage" to "75",
        )
    ),

    /** No dynamic GI at all, SSR reflections, very coarse Nanite, lower resolution. */
    PERFORMANCE(
        "performance",
        BALANCED.cvars + linkedMapOf(
            "r.DynamicGlobalIlluminationMethod" to "0",
            "r.Nanite.MaxPixelsPerEdge" to "8",
            "r.VolumetricFog" to "0",
            "r.DepthOfFieldQuality" to "0",
            "r.ScreenPercentage" to "60",
        )
    ),

    /**
     * For games where Lumen/Nanite render garbage or nothing: removes every async
     * and ray-tracing path and turns dynamic GI off, but leaves Nanite enabled so
     * the game does not switch to (often very low quality) fallback meshes.
     */
    COMPATIBILITY(
        "compat",
        linkedMapOf(
            "r.RDG.AsyncCompute" to "0",
            "r.Lumen.AsyncCompute" to "0",
            "r.Lumen.HardwareRayTracing" to "0",
            "r.Nanite.AsyncRasterization" to "0",
            "r.Shadow.Virtual.Enable" to "0",
            "r.DynamicGlobalIlluminationMethod" to "0",
            "r.ReflectionMethod" to "2",
        )
    ),

    /** Same as COMPATIBILITY, plus Nanite off: the game uses its fallback meshes. */
    NO_NANITE(
        "no_nanite",
        COMPATIBILITY.cvars + linkedMapOf(
            "r.Nanite" to "0",
        )
    );

    companion object {
        @JvmField
        val DEFAULT: Ue5Preset = BALANCED

        @JvmStatic
        fun fromId(id: String?): Ue5Preset =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DEFAULT

        @JvmStatic
        fun ids(): List<String> = entries.map { it.id }

        /**
         * Parses user cvars. Accepts "r.X=1", "r.X 1", separated by commas,
         * semicolons or newlines. Invalid tokens are ignored.
         */
        @JvmStatic
        fun parseCustom(text: String?): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            if (text.isNullOrBlank()) return out
            for (raw in text.split(',', ';', '\n', '\r')) {
                val token = raw.trim()
                if (token.isEmpty() || token.startsWith("//") || token.startsWith("#")) continue
                val eq = token.indexOf('=')
                val (name, value) = if (eq > 0) {
                    token.substring(0, eq).trim() to token.substring(eq + 1).trim()
                } else {
                    val sp = token.indexOfFirst { it.isWhitespace() }
                    if (sp <= 0) continue
                    token.substring(0, sp).trim() to token.substring(sp + 1).trim()
                }
                if (!isValidCvarName(name) || value.isEmpty() || value.any { it == ',' || it == '"' }) continue
                out[name] = value
            }
            return out
        }

        /** Preset cvars with user cvars applied on top (user wins). */
        @JvmStatic
        fun resolve(preset: Ue5Preset, custom: String?): Map<String, String> =
            LinkedHashMap(preset.cvars).apply { putAll(parseCustom(custom)) }

        private fun isValidCvarName(name: String): Boolean =
            name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }
}
