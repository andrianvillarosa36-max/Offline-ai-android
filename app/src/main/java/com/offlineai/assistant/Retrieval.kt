package com.offlineai.assistant

import kotlin.math.ln

/** Text helpers shared by document search (and, later, keyword extraction). */
object Retrieval {
    internal val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for",
        "with", "about", "as", "into", "from", "is", "are", "was", "were", "be", "been", "being",
        "it", "its", "this", "that", "these", "those", "i", "you", "he", "she", "we", "they",
        "them", "his", "her", "our", "their", "my", "your", "do", "does", "did", "what", "which",
        "who", "whom", "how", "why", "when", "where", "can", "could", "should", "would", "will",
        "shall", "may", "might", "not", "no", "so", "than", "then", "there", "here", "also",
        "have", "has", "had", "me", "us", "tell", "say", "says", "said",
        "after", "before", "every", "each", "among", "between", "during", "over", "under",
        "until", "while", "because", "since", "through", "both", "some", "any", "all", "more",
        "most", "other", "such", "only", "own", "same", "very", "just", "too", "out", "off",
        "again", "once", "per", "via", "within", "without", "above", "below", "against", "upon",
        "whether", "either", "neither", "another", "many", "much", "few", "several", "near",
        "like", "make", "made", "use", "used", "need", "needs", "must", "get", "gets", "one"
    )

    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")

    /** Lowercase words without common filler words. */
    fun terms(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toList()

    /** Splits text into overlapping chunks of about [size] words. */
    fun chunk(text: String, size: Int = 160, overlap: Int = 30): List<String> {
        val words = text.split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val step = size - overlap
        var start = 0
        while (start < words.size) {
            val end = minOf(words.size, start + size)
            out.add(words.subList(start, end).joinToString(" "))
            if (end == words.size) break
            start += step
        }
        return out
    }
}

/** Small BM25 search over text chunks. No models, no network, works instantly. */
class Bm25Index(private val chunks: List<String>) {
    private val termCounts = ArrayList<Map<String, Int>>(chunks.size)
    private val lengths = IntArray(chunks.size)
    private val docFreq = HashMap<String, Int>()
    private val avgLength: Double

    init {
        var total = 0L
        for ((i, chunk) in chunks.withIndex()) {
            val counts = HashMap<String, Int>()
            val terms = Retrieval.terms(chunk)
            for (t in terms) counts[t] = (counts[t] ?: 0) + 1
            for (t in counts.keys) docFreq[t] = (docFreq[t] ?: 0) + 1
            termCounts.add(counts)
            lengths[i] = terms.size
            total += terms.size
        }
        avgLength = if (chunks.isEmpty()) 1.0 else maxOf(1.0, total.toDouble() / chunks.size)
    }

    /** Indices of the best matching chunks, in document order. Empty if nothing matches. */
    fun search(query: String, k: Int): List<Int> {
        val queryTerms = Retrieval.terms(query).distinct()
        if (queryTerms.isEmpty() || chunks.isEmpty()) return emptyList()

        val n = chunks.size.toDouble()
        val scores = DoubleArray(chunks.size)
        for (t in queryTerms) {
            val df = docFreq[t] ?: continue
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            for (i in chunks.indices) {
                val f = termCounts[i][t] ?: continue
                val norm = f * (K1 + 1.0) / (f + K1 * (1.0 - B + B * lengths[i] / avgLength))
                scores[i] += idf * norm
            }
        }
        return chunks.indices
            .filter { scores[it] > 0.0 }
            .sortedByDescending { scores[it] }
            .take(k)
            .sorted()
    }

    private companion object {
        const val K1 = 1.5
        const val B = 0.75
    }
}
