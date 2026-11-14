package app.shilex.android.ui

/**
 * Shows Hindi (Devanagari) in English letters, the way people type Hinglish:
 * "क्या कर रहे हो" becomes "kya kar rahe ho". Anything that isn't Devanagari is left as it is.
 */
object Romanize {
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'ळ' to "l",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f",
    )
    private val nukta = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )
    private val matras = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'े' to "e", 'ै' to "ai",
        'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ृ' to "ri",
    )
    private const val VIRAMA = '\u094D'
    private const val NUKTA = '\u093C'

    private fun isDevanagari(c: Char) = c in '\u0900'..'\u097F'

    /** True when the next character still belongs to the same Hindi word. */
    private fun continuesWord(next: Char?) = next != null && next in '\u0900'..'\u0963'

    fun text(input: String): String {
        if (input.none { isDevanagari(it) }) return input
        val s = input.replace("ज्ञ", "ग्य")
        val out = StringBuilder()
        var seenVowel = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val base = consonants[c]
            if (base != null) {
                var j = i + 1
                var letter: String = base
                if (s.getOrNull(j) == NUKTA) {
                    letter = nukta[c] ?: base
                    j++
                }
                val next = s.getOrNull(j)
                when {
                    next == VIRAMA -> {
                        out.append(letter)
                        j++
                    }
                    next != null && matras.containsKey(next) -> {
                        out.append(letter).append(matras.getValue(next))
                        seenVowel = true
                        j++
                    }
                    else -> {
                        out.append(letter)
                        if (continuesWord(next)) {
                            // karata -> karta, tumase -> tumse: drop the quiet "a" in the middle of a word
                            val after = if (next != null && consonants.containsKey(next)) {
                                s.getOrNull(if (s.getOrNull(j + 1) == NUKTA) j + 2 else j + 1)
                            } else {
                                null
                            }
                            val quiet = seenVowel && after != null && matras.containsKey(after)
                            if (!quiet) out.append('a')
                            seenVowel = true
                        }
                    }
                }
                i = j
                continue
            }
            when {
                vowels.containsKey(c) -> {
                    out.append(vowels.getValue(c))
                    seenVowel = true
                }
                c == 'ं' || c == 'ँ' -> {
                    if (out.endsWith("e")) {
                        out.setLength(out.length - 1)
                        out.append("ein")
                    } else {
                        out.append('n')
                    }
                }
                c == 'ः' -> out.append('h')
                c == '।' -> out.append('.')
                else -> {
                    out.append(c)
                    if (!isDevanagari(c)) seenVowel = false
                }
            }
            i++
        }
        return out.toString()
    }
}
