package com.keyguard.infer

/**
 * One decoded entity, located against the original message text.
 *
 * [bucket] is the coarse term the rule table references — `exact_location`, `contact_info` and so
 * on. [entity] is the fine-grained type, kept because the overlay may want to say *what* it found
 * and because an unmapped entity should be visible rather than silently dropped.
 */
data class EntitySpan(
    val entity: String,
    val range: IntRange,
    val bucket: String?,
)

/**
 * Turns a per-token tag distribution into character spans.
 *
 * Two things here are less obvious than they look.
 *
 * **Continuations are tagged, and the decoder merges.** An earlier version of the training data
 * left wordpiece continuations unsupervised, on the argument that labelling them inflates
 * token-level accuracy. True, and irrelevant — the headline metric is entity-level — while the
 * cost was severe: with nothing supervising continuations the model had no way to express where an
 * entity *ends*, so it emitted `B-` on every subword and `604-555-0182` decoded as three separate
 * phone numbers. The risk engine tolerated that, because it only asks whether `contact_info` is
 * present; the overlay did not, because it underlines the span.
 *
 * **Spans that touch are joined.** Nothing can begin mid-word, so two same-type spans with no
 * character between them are one entity that the tokenizer happened to split. This is what turns
 * an undertrained model's `cr` + `##anbrook` back into one `cranbrook`. A space *is* a character,
 * so "cranbrook ave" still relies on the model emitting a proper `B-` `I-` sequence — the merge
 * repairs tokenization artefacts, not model mistakes.
 */
object BioDecoder {

    /**
     * @param tokenProbabilities `[position][tag]`, already softmaxed by the graph.
     * @param encoding the encoding those positions came from, carrying the offsets.
     */
    fun decode(
        tokenProbabilities: Array<FloatArray>,
        encoding: Encoding,
        contract: LabelContract,
    ): List<EntitySpan> {
        val raw = mutableListOf<EntitySpan>()
        var currentEntity: String? = null
        var currentRange: IntRange? = null

        fun close() {
            val entity = currentEntity
            val range = currentRange
            if (entity != null && range != null) {
                raw += EntitySpan(entity, range, contract.entityToSafetyEntity[entity])
            }
            currentEntity = null
            currentRange = null
        }

        for (position in encoding.tokens.indices) {
            if (encoding.attentionMask[position] == 0) break
            val offset = encoding.offsets[position] ?: continue // [CLS] / [SEP]
            if (position >= tokenProbabilities.size) break

            val tag = contract.piiTags[argmax(tokenProbabilities[position])]
            if (tag == OUTSIDE) {
                close()
                continue
            }

            val prefix = tag.substring(0, 1)
            val entity = tag.substring(2)

            if (prefix == "I" && currentEntity == entity) {
                currentRange = minOf(currentRange!!.first, offset.first)..maxOf(currentRange!!.last, offset.last)
            } else {
                // A `B-` always opens. So does a dangling `I-` with no matching open span: the
                // model has said "this is part of an entity" and refusing to record it because
                // the sequence is malformed would lose a real detection to a formatting rule.
                close()
                currentEntity = entity
                currentRange = offset
            }
        }
        close()

        return merge(raw, contract)
    }

    /** The coarse terms the rule table can reference, for a decoded message. */
    fun bucketsOf(spans: List<EntitySpan>): Set<String> =
        spans.mapNotNullTo(mutableSetOf()) { it.bucket }

    private fun merge(spans: List<EntitySpan>, contract: LabelContract): List<EntitySpan> {
        if (spans.size < 2) return spans
        val sorted = spans.sortedBy { it.range.first }
        val out = mutableListOf<EntitySpan>()
        var open = sorted.first()
        for (next in sorted.drop(1)) {
            val touching = next.range.first <= open.range.last + 1
            if (next.entity == open.entity && touching) {
                open = open.copy(
                    range = open.range.first..maxOf(open.range.last, next.range.last),
                )
            } else {
                out += open
                open = next
            }
        }
        out += open
        return out.map { it.copy(bucket = contract.entityToSafetyEntity[it.entity]) }
    }

    private fun argmax(row: FloatArray): Int {
        var best = 0
        for (i in 1 until row.size) if (row[i] > row[best]) best = i
        return best
    }

    private const val OUTSIDE = "O"
}
