package org.schabi.parakeetype.settings.model

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tests for [ModelImporter.install] — the pure, JVM-only archive install step. */
class ModelImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val encoder = ByteArray(300_000) { (it % 251).toByte() }
    private val vocab = "▁the 0\n▁a 1\n".toByteArray()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val files = listOf(
        ModelFile("encoder.onnx", sha(encoder)),
        ModelFile("vocab.txt", sha(vocab)),
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val modelsRoot get() = tmp.root
    private val modelDir get() = File(modelsRoot, "model")
    private val stagingDir get() = File(modelsRoot, ".import-model")

    private fun install(archive: ByteArray, progress: (Long) -> Unit = {}): ImportResult = runBlocking {
        ModelImporter.install(ByteArrayInputStream(archive), modelDir, stagingDir, files, progress)
    }

    @Test
    fun `complete archive installs every file and removes staging`() {
        val result = install(zip("encoder.onnx" to encoder, "vocab.txt" to vocab))

        assertThat(result).isEqualTo(ImportResult.Success)
        assertThat(File(modelDir, "encoder.onnx").readBytes()).isEqualTo(encoder)
        assertThat(File(modelDir, "vocab.txt").readBytes()).isEqualTo(vocab)
        assertThat(stagingDir).doesNotExist()
    }

    @Test
    fun `files inside a folder and unrelated entries are handled`() {
        val result = install(
            zip(
                "parakeet/README.md" to "hi".toByteArray(),
                "parakeet/encoder.onnx" to encoder,
                "parakeet/vocab.txt" to vocab,
            )
        )

        assertThat(result).isEqualTo(ImportResult.Success)
        assertThat(modelDir.list()).containsExactlyInAnyOrder("encoder.onnx", "vocab.txt")
    }

    @Test
    fun `replaces a previous partial installation`() {
        modelDir.mkdirs()
        File(modelDir, "encoder.onnx.tmp").writeText("stale partial download")

        assertThat(install(zip("encoder.onnx" to encoder, "vocab.txt" to vocab))).isEqualTo(ImportResult.Success)
        assertThat(modelDir.list()).containsExactlyInAnyOrder("encoder.onnx", "vocab.txt")
    }

    @Test
    fun `checksum mismatch is rejected and nothing is installed`() {
        val corrupted = encoder.copyOf().also { it[1000] = (it[1000] + 1).toByte() }

        val result = install(zip("encoder.onnx" to corrupted, "vocab.txt" to vocab))

        assertThat(result).isEqualTo(ImportResult.ChecksumMismatch(listOf("encoder.onnx")))
        assertThat(modelDir).doesNotExist()
        assertThat(stagingDir).doesNotExist()
    }

    @Test
    fun `missing file is reported and nothing is installed`() {
        val result = install(zip("encoder.onnx" to encoder))

        assertThat(result).isEqualTo(ImportResult.MissingFiles(listOf("vocab.txt")))
        assertThat(modelDir).doesNotExist()
    }

    @Test
    fun `a non-zip file is not an archive`() {
        assertThat(install("definitely not a zip".toByteArray())).isEqualTo(ImportResult.NotAnArchive)
        assertThat(install(zip("other.bin" to byteArrayOf(1, 2, 3)))).isEqualTo(ImportResult.NotAnArchive)
        assertThat(modelDir).doesNotExist()
    }

    @Test
    fun `progress reports consumed archive bytes`() {
        // Random bytes don't compress, so the archive is > 3 MB and crosses the 1 MB report step.
        val big = ByteArray(3 shl 20).also { java.util.Random(42).nextBytes(it) }
        val archive = zip("encoder.onnx" to big, "vocab.txt" to vocab)
        val bigFiles = listOf(ModelFile("encoder.onnx", sha(big)), ModelFile("vocab.txt", sha(vocab)))
        val reported = mutableListOf<Long>()

        val result = runBlocking {
            ModelImporter.install(ByteArrayInputStream(archive), modelDir, stagingDir, bigFiles) { reported += it }
        }

        assertThat(result).isEqualTo(ImportResult.Success)
        assertThat(reported).isNotEmpty.isSorted
        assertThat(reported.last()).isLessThanOrEqualTo(archive.size.toLong())
    }
}
