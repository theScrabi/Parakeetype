package dev.brgr.outspoke.settings.model

/**
 * The pinned release that hosts the single-file model archives. The app itself never
 * contacts it (it has no INTERNET permission) - the URL is only handed to the user's
 * browser. Build the archive with `devtools/package-model.sh`.
 */
private const val MODEL_ARCHIVE_RELEASE =
    "https://github.com/minburg/outspoke-data/releases/download/model-parakeet-v3"

/**
 * One file of an installed model.
 *
 * @param filename Name of the file inside the model directory (and inside the archive).
 * @param sha256   Expected lowercase-hex SHA-256 digest, or `null` to skip verification.
 */
data class ModelFile(
    val filename: String,
    val sha256: String? = null,
)

/**
 * All metadata needed to display, install, store, and identify a speech model.
 *
 * A model is installed from a **single ZIP archive** that the user downloads in their
 * browser from [archiveUrl] and then imports via the system file picker
 * ([ModelImporter]). Every entry is matched by file name (directories inside the archive
 * are ignored) and verified against [ModelFile.sha256].
 */
data class ModelInfo(
    val id: ModelId,
    val displayName: String,
    val description: String,
    /** Approximate installed size displayed in the UI. */
    val approximateSizeMb: Int,
    /** Browser download URL of the single-file model archive. */
    val archiveUrl: String,
    /** Files the archive must contain; all must be present for the model to be ready. */
    val files: List<ModelFile>,
) {
    /** Files that must be present and non-empty for [ModelStorageManager.isModelReady]. */
    val requiredFiles: List<String> get() = files.map { it.filename }
}

/**
 * Central registry of all supported speech recognition models.
 *
 * Add a new [ModelInfo] entry here and a corresponding [ModelId] value to expose a new
 * model in the model-management UI without touching any other code.
 */
object ModelRegistry {

    //    val all: List<ModelInfo> = listOf(parakeetV3, voxtralMini, whisperLargeV3Turbo)
    val all: List<ModelInfo> =
        listOf(parakeetV3) // for now removed voxtralMini and whisperLargeV3Turbo as I could not get it to run on-device within reasonable resource limits

    private val byId: Map<ModelId, ModelInfo> = all.associateBy { it.id }

    /** Returns the [ModelInfo] for [id], throwing if the ID is not registered. */
    operator fun get(id: ModelId): ModelInfo =
        byId[id] ?: error("ModelRegistry: unknown ModelId $id")
}

private val parakeetV3 = ModelInfo(
    id = ModelId.PARAKEET_V3,
    displayName = "Parakeet-V3 (Default)",
    description = "NeMo TDT model for on-device ASR optimised for English and 24 other european languages like DE, FR, ES, IT, RU etc. " +
            "Very fast and compact - the recommended choice for most devices.",
    approximateSizeMb = 700,
    // Built from https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx by devtools/package-model.sh.
    archiveUrl = "$MODEL_ARCHIVE_RELEASE/parakeet-tdt-0.6b-v3-int8.zip",
    files = listOf(
        ModelFile("encoder-model.int8.onnx", "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09"),
        ModelFile("decoder_joint-model.int8.onnx", "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70"),
        ModelFile("nemo128.onnx", "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"),
        ModelFile("config.json", "666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466"),
        ModelFile("vocab.txt", "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    ),
)

// Q4-quantised variant: audio_encoder_q4 (661 MB) + embed_tokens_q4 (258 MB)
//                       + decoder_model_merged_q4 (~2.3 GB) + tokenizer.json
// Total ≈ 3 300 MB - suitable only for high-end devices with ≥ 6 GB RAM.
// Source: https://huggingface.co/onnx-community/Voxtral-Mini-4B-Realtime-2602-ONNX (no archive published).
@Suppress("unused")
private val voxtralMini = ModelInfo(
    id = ModelId.VOXTRAL_MINI,
    displayName = "Voxtral Mini 4B (Q4)",
    description = "Mistral-based multilingual speech model from onnx-community. " +
            "Q4-quantised (~3.3 GB) - requires a high-end device with ample storage.",
    approximateSizeMb = 3_300,
    archiveUrl = "$MODEL_ARCHIVE_RELEASE/voxtral-mini-4b-q4.zip",
    files = listOf(
        ModelFile("audio_encoder_q4.onnx"),
        ModelFile("audio_encoder_q4.onnx_data"),
        ModelFile("embed_tokens_q4.onnx"),
        ModelFile("embed_tokens_q4.onnx_data"),
        ModelFile("decoder_model_merged_q4.onnx"),
        ModelFile("decoder_model_merged_q4.onnx_data"),
        ModelFile("decoder_model_merged_q4.onnx_data_1"),
        ModelFile("tokenizer.json"),
        ModelFile("tokenizer_config.json"),
        ModelFile("generation_config.json"),
    ),
)

// Whisper Large v3 Turbo INT8 - real file sizes (verified via HuggingFace API):
//   encoder_model_int8.onnx          644 822 094 B ≈ 615 MB
//   decoder_model_merged_int8.onnx   439 936 716 B ≈ 420 MB  (merged branches + full vocab table)
//   tokenizer.json                     2 480 617 B ≈   2 MB
//   Total                                          ≈ 1 037 MB ≈ 1.1 GB
// Same onnx-community optimum export format → tensor names match WhisperEngine directly.
// Source: https://huggingface.co/onnx-community/whisper-large-v3-turbo (no archive published).
@Suppress("unused")
private val whisperLargeV3Turbo = ModelInfo(
    id = ModelId.WHISPER_SMALL,   // reuses existing ModelId slot
    displayName = "Whisper Large-v3 Turbo (INT8)",
    description = "OpenAI Whisper Large-v3 encoder with a 2-layer turbo decoder. " +
            "Near-Large-v3 accuracy, multilingual, INT8-quantised (~1.1 GB).",
    approximateSizeMb = 1_037,
    archiveUrl = "$MODEL_ARCHIVE_RELEASE/whisper-large-v3-turbo-int8.zip",
    files = listOf(
        ModelFile("encoder_model_int8.onnx"),
        ModelFile("decoder_model_merged_int8.onnx"),
        ModelFile("tokenizer.json"),
    ),
)
