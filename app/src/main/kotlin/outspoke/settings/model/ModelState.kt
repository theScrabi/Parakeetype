package dev.brgr.outspoke.settings.model

/** All possible states of a local speech model. */
sealed class ModelState {
    /** Model files are absent from internal storage. */
    object NotDownloaded : ModelState()

    /** A model archive is being imported. [progressFraction] is in [0.0, 1.0]. */
    data class Importing(val progressFraction: Float) : ModelState()

    /** All model files are present and passed integrity verification. */
    object Ready : ModelState()
}
