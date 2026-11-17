package com.rullino.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Ordine dei settori dei colori selettivi: deve coincidere con lo shader. */
val HUE_KEYS = listOf("red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta")

@Serializable
data class HslAdj(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f)

@Serializable
data class SplitTone(
    val shadowHue: Float = 210f,
    val shadowAmount: Float = 0f,
    val highlightHue: Float = 40f,
    val highlightAmount: Float = 0f,
    val balance: Float = 0f,
)

@Serializable
data class MonoMix(
    val enabled: Boolean = false,
    val red: Float = 30f,
    val green: Float = 59f,
    val blue: Float = 11f,
)

@Serializable
data class GrainParams(val amount: Float = 20f, val size: Float = 1.2f, val roughness: Float = 50f)

/** Parametri come li esporta Camera Oscura (unità dell'interfaccia, −100…100 salvo eccezioni). */
@Serializable
data class FilmParams(
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val gamma: Float = 1f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val fade: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val hsl: Map<String, HslAdj> = emptyMap(),
    val split: SplitTone = SplitTone(),
    val mono: MonoMix = MonoMix(),
    val grain: GrainParams = GrainParams(),
    val vignette: Float = 0f,
    /** "negative" (predefinito) o "slide": decide come la pellicola reagisce agli errori di esposizione. */
    val process: String = "",
    /** Alone rosso attorno alle luci forti, 0…100 (tipo CineStill). */
    val halation: Float = 0f,
)

@Serializable
data class Preset(
    val id: String = "",
    val name: String = "",
    val iso: Int = 400,
    val exposures: Int = 36,
    val note: String = "",
    val params: FilmParams = FilmParams(),
)

@Serializable
data class ManifestFiles(
    val preset: String = "preset.json",
    val box: String? = null,
    val canister: String? = null,
    val demos: List<String> = emptyList(),
    val lut: String? = null,
)

/** manifest.json di un file .emulsion (formatVersion 1). */
@Serializable
data class Manifest(
    val format: String = "",
    val formatVersion: Int = 1,
    val id: String = "",
    val name: String = "",
    val iso: Int = 400,
    val exposures: Int = 36,
    val type: String = "color",
    val note: String = "",
    val files: ManifestFiles = ManifestFiles(),
    val sha256: Map<String, String?> = emptyMap(),
)

/** Una riga del catalogo del negozio (tabella `emulsions` su Supabase). */
@Serializable
data class CatalogItem(
    val id: String,
    val name: String,
    val iso: Int = 400,
    val exposures: Int = 36,
    val type: String = "color",
    val note: String = "",
    val version: Int = 1,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("price_cents") val priceCents: Int = 0,
    @SerialName("box_url") val boxUrl: String? = null,
    @SerialName("file_url") val fileUrl: String,
)
