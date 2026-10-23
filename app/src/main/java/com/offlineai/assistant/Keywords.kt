package com.offlineai.assistant

/** Instant keyword extraction. Pure word counting, no model needed. */
object Keywords {
    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*")
    private val SENTENCE_BREAK = Regex("[.!?;:\\n\\r]+")

    fun extract(text: String, max: Int = 12): List<String> {
        val uni = HashMap<String, Int>()
        val bi = HashMap<String, Int>()
        var total = 0

        for (sentence in text.split(SENTENCE_BREAK)) {
            var prev: String? = null
            for (m in WORD.findAll(sentence.lowercase())) {
                val w = m.value.trim('\'', '-')
                total++
                val ok = w.length >= 3 && w !in Retrieval.STOP_WORDS && !w.all { it.isDigit() }
                if (ok) {
                    uni[w] = (uni[w] ?: 0) + 1
                    val p = prev
                    if (p != null) {
                        val key = "$p $w"
                        bi[key] = (bi[key] ?: 0) + 1
                    }
                    prev = w
                } else {
                    prev = null
                }
            }
        }

        // Fold plurals into the singular: "chapels" counts toward "chapel".
        for (w in uni.keys.toList()) {
            if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) {
                val single = w.dropLast(1)
                val c = uni[single]
                if (c != null) {
                    uni[single] = c + (uni[w] ?: 0)
                    uni.remove(w)
                }
            }
        }

        val byCountThenName = compareBy<Map.Entry<String, Int>>({ -it.value }, { it.key })
        val minFreq = if (total < 150) 1 else 2

        // Two-word phrases that repeat are usually the best keywords.
        val phrases = bi.entries.filter { it.value >= 2 }
            .sortedWith(byCountThenName)
            .take(max / 2)
            .map { it.key }
        val covered = phrases.flatMap { it.split(" ") }.toSet()

        val words = uni.entries.filter { it.value >= minFreq && it.key !in covered }
            .sortedWith(byCountThenName)
            .map { it.key }

        return (phrases + words).take(max)
    }
}
