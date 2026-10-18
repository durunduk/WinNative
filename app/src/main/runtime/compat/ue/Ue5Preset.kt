package com.winlator.cmod.runtime.compat.ue

/**
 * Console-variable presets for Unreal Engine 5 games.
 *
 * Every preset only uses engine cvars that a shipping build reads from
 * Engine.ini [SystemSettings] (and from -dpcvars). Cvars flagged as cheats are
 * deliberately avoided because shipping builds ignore them.
 *
 * P1..P4 trade quality for FPS, each building on the previous one.
 * DBG1..DBG3 work around rendering artifacts and may cost FPS.
 * The settings UI lists presets by ordinal, so keep its labels in this order.
 */
enum class Ue5Preset(val id: String, val cvars: Map<String, String>) {

    // ---- Performance presets P1..P4 -------------------------------------------
    // Lessons from Styx: Blades of Greed (UE 5.6, Adreno 840): turning Virtual
    // Shadow Maps or Nanite OFF makes UE5 games much slower (classic shadow maps
    // and fallback meshes render full-detail geometry; FPS fell from ~40 to 0.1-3).
    // So the P presets keep both on and only make them cheaper.

    /** Barely visible: softer-shadow ray counts, coarser fog grid, no motion blur. */
    P1(
        "p1",
        linkedMapOf(
            "r.Lumen.HardwareRayTracing" to "0",
            "r.Lumen.TraceMeshSDFs" to "0",
            "r.Shadow.Virtual.SMRT.RayCountDirectional" to "4",
            "r.Shadow.Virtual.SMRT.SamplesPerRayDirectional" to "4",
            "r.Shadow.Virtual.SMRT.RayCountLocal" to "4",
            "r.Shadow.Virtual.SMRT.SamplesPerRayLocal" to "4",
            "r.VolumetricFog.GridPixelSize" to "16",
            "r.MotionBlurQuality" to "0",
        )
    ),

    /** 75% resolution, coarser Lumen GI, SSR reflections, lower shadow resolution. */
    P2(
        "p2",
        P1.cvars + linkedMapOf(
            "r.ScreenPercentage" to "75",
            "r.Lumen.ScreenProbeGather.DownsampleFactor" to "32",
            "r.ReflectionMethod" to "2",
            "r.Shadow.Virtual.ResolutionLodBiasDirectional" to "1",
            "r.Shadow.Virtual.SMRT.RayCountDirectional" to "2",
            "r.Shadow.Virtual.SMRT.SamplesPerRayDirectional" to "2",
            "r.Shadow.Virtual.SMRT.RayCountLocal" to "2",
            "r.Shadow.Virtual.SMRT.SamplesPerRayLocal" to "2",
            "r.Nanite.MaxPixelsPerEdge" to "2",
        )
    ),

    /** 60% resolution, hard shadows, no volumetric fog or depth of field. Lumen stays. */
    P3(
        "p3",
        P2.cvars + linkedMapOf(
            "r.ScreenPercentage" to "60",
            "r.Lumen.ScreenProbeGather.TracingOctahedronResolution" to "4",
            "r.Shadow.Virtual.ResolutionLodBiasDirectional" to "2",
            "r.Shadow.Virtual.SMRT.RayCountDirectional" to "0",
            "r.Shadow.Virtual.SMRT.RayCountLocal" to "0",
            "r.Nanite.MaxPixelsPerEdge" to "4",
            "r.VolumetricFog" to "0",
            "r.DepthOfFieldQuality" to "0",
        )
    ),

    /** Maximum FPS: Lumen GI off (lighting changes, interiors get flatter), 50% resolution. */
    P4(
        "p4",
        P3.cvars + linkedMapOf(
            "r.ScreenPercentage" to "50",
            "r.DynamicGlobalIlluminationMethod" to "0",
            "r.Shadow.Virtual.ResolutionLodBiasDirectional" to "3",
            "r.Nanite.MaxPixelsPerEdge" to "8",
        )
    ),

    // ---- Artifact workarounds DBG1..DBG3 (may cost FPS) ------------------------
    // Rendering garbage is often a translation-layer bug first: e.g. Styx needed
    // vkd3d-proton 0bb64c56+ and nothing in Engine.ini helped. Try that first.

    /** Serialises async compute and Nanite async rasterization, no HW ray tracing. */
    DBG1(
        "dbg1",
        linkedMapOf(
            "r.RDG.AsyncCompute" to "0",
            "r.Lumen.DiffuseIndirect.AsyncCompute" to "0",
            "r.Lumen.Reflections.AsyncCompute" to "0",
            "r.Nanite.AsyncRasterization" to "0",
            "r.Lumen.HardwareRayTracing" to "0",
        )
    ),

    /** DBG1 plus Lumen off (GI and reflections): for black, flickering or noisy lighting. */
    DBG2(
        "dbg2",
        DBG1.cvars + linkedMapOf(
            "r.DynamicGlobalIlluminationMethod" to "0",
            "r.ReflectionMethod" to "2",
        )
    ),

    /** DBG2 plus Nanite off (fallback meshes). Last resort: FPS can drop to single digits. */
    DBG3(
        "dbg3",
        DBG2.cvars + linkedMapOf(
            "r.Nanite" to "0",
        )
    ),

    /** No preset cvars at all: only what the user types into the custom field. */
    CUSTOM_ONLY("custom", emptyMap());

    companion object {
        @JvmField
        val DEFAULT: Ue5Preset = P2

        /** Ids stored by earlier versions; their old presets switched shadows/Nanite off. */
        private val LEGACY_IDS = mapOf(
            "light" to P1,
            "balanced" to P2,
            "performance" to P3,
            "compat" to DBG1,
            "no_nanite" to DBG3,
        )

        @JvmStatic
        fun fromId(id: String?): Ue5Preset {
            val key = id?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.id == key } ?: LEGACY_IDS[key] ?: DEFAULT
        }

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
