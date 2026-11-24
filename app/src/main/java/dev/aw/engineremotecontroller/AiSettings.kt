package dev.aw.engineremotecontroller

import android.content.Context

data class AiConfig(
    val enabled: Boolean = false,
    val mode: AiMode = AiMode.AUTO,
    val manualSeconds: Int = 3,
    val targets: Set<DetectionClass> = setOf(DetectionClass.ANIMAL),
    val threshold: Float = 0.30f,
    val window: Int = 3,
    val required: Int = 2,
    val suppressionSeconds: Int = 60,
    val saveImage: Boolean = true,
    val notify: Boolean = true
)

internal class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun load(): AiConfig {
        val window = prefs.getInt("window", 3).coerceIn(1, 10)
        val targets = prefs.getStringSet("targets", setOf("ANIMAL")).orEmpty()
        return AiConfig(
            prefs.getBoolean("enabled", false),
            AiMode.values().getOrElse(prefs.getInt("mode", 0)) { AiMode.AUTO },
            prefs.getInt("interval", 3).coerceIn(1, 10),
            DetectionClass.values().filter { it.name in targets }.toSet(),
            prefs.getFloat("threshold", 0.30f).takeIf { it in 0.01f..1f } ?: 0.30f,
            window, prefs.getInt("required", 2).coerceIn(1, window),
            prefs.getInt("suppression", 60).coerceIn(0, 3600),
            prefs.getBoolean("image", true), prefs.getBoolean("notify", true)
        )
    }

    fun save(config: AiConfig) {
        prefs.edit().putBoolean("enabled", config.enabled).putInt("mode", config.mode.ordinal)
            .putInt("interval", config.manualSeconds).putStringSet("targets", config.targets.map { it.name }.toSet())
            .putFloat("threshold", config.threshold).putInt("window", config.window)
            .putInt("required", config.required).putInt("suppression", config.suppressionSeconds)
            .putBoolean("image", config.saveImage).putBoolean("notify", config.notify).apply()
    }
}
