package com.keyguard.infer

import android.content.Context
import java.io.File

/**
 * Gets the shipped model onto local storage and its sidecars into memory.
 *
 * The sidecars are small and are read straight out of the APK every time. The model is not: it is
 * 67.6 MB, and how it gets opened is a real decision rather than plumbing.
 *
 * **Why it is copied rather than read from assets.** ONNX Runtime wants a file path or a byte
 * buffer. Reading a 67.6 MB asset into a `ByteArray` means a 67.6 MB allocation on a device that
 * may have very little headroom — and the low-end hardware this product is most likely to run on
 * is exactly where that fails. Copying once to internal storage and handing the runtime a path
 * lets it map the file instead, so the model never passes through the Java heap at all.
 *
 * **Why the APK must not compress it.** An AAPT-compressed asset cannot be read in place; the
 * platform has to inflate it to a real file before anything can map it, which costs the storage
 * twice and shows up as startup latency. `android.androidResources.noCompress += "onnx"` in the
 * consuming app keeps it mappable — without that line this copy still works, it is just slower and
 * the APK is no smaller for it.
 *
 * The copy is stamped with the export's checkpoint identity, so a model update actually replaces
 * the old file instead of silently keeping it forever.
 */
object ModelAssets {

    const val DIRECTORY = "model"
    const val MODEL_FILE = "model_int8.onnx"

    private const val STAMP_FILE = "model.stamp"

    fun readText(context: Context, name: String): String =
        context.assets.open("$DIRECTORY/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    fun tokenizer(context: Context): WordPieceTokenizer =
        context.assets.open("$DIRECTORY/vocab.txt").use { stream ->
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                WordPieceTokenizer(WordPieceTokenizer.vocabularyFrom(lines))
            }
        }

    fun contract(context: Context): LabelContract =
        ModelJson.decodeFromString(LabelContract.serializer(), readText(context, "label_contract.json"))

    fun thresholds(context: Context): Thresholds =
        ModelJson.decodeFromString(Thresholds.serializer(), readText(context, "thresholds.json"))

    fun riskEngine(context: Context): RiskEngine =
        RiskEngine(ModelJson.decodeFromString(RiskRuleTable.serializer(), readText(context, "risk_rules.json")))

    /**
     * Returns the on-disk model, copying it out of the APK the first time and whenever the
     * shipped export changes.
     *
     * Copying to a temporary file and renaming means an interrupted copy — the process dying
     * mid-write — leaves no half-written model that would load and then behave strangely. It
     * either replaces the file completely or leaves the previous one alone.
     */
    fun modelFile(context: Context): File {
        val destination = File(context.filesDir, MODEL_FILE)
        val stamp = File(context.filesDir, STAMP_FILE)
        val expected = exportStamp(context)

        if (destination.isFile && stamp.isFile && stamp.readText() == expected) {
            return destination
        }

        val temporary = File(context.filesDir, "$MODEL_FILE.partial")
        context.assets.open("$DIRECTORY/$MODEL_FILE").use { source ->
            temporary.outputStream().use { sink -> source.copyTo(sink, DEFAULT_BUFFER_SIZE) }
        }
        check(temporary.renameTo(destination)) {
            "could not move the extracted model into place at $destination"
        }
        stamp.writeText(expected)
        return destination
    }

    /**
     * Identity of the shipped export.
     *
     * The manifest records the checkpoint, the parameter counts and the thresholds, so any
     * re-export changes it. Using the manifest rather than a hand-maintained version number means
     * nobody has to remember to bump anything.
     */
    private fun exportStamp(context: Context): String =
        readText(context, "export_manifest.json").hashCode().toString()
}
