package ng.ainow.jarvis.language
/**
 * Turns a spoken phone number into digits: "470", "0803 123 4567", "plus 234 803...",
 * "zero eight oh three", "double four seven". Returns null if it isn't a number (a contact name).
 */
object PhoneNumber {
    private val DIGIT_WORDS = mapOf(
        "zero" to '0', "oh" to '0', "o" to '0', "nought" to '0',
        "one" to '1', "two" to '2', "three" to '3', "four" to '4', "five" to '5',
        "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9'
    )
    private val REPEAT = mapOf("double" to 2, "triple" to 3)

    /** Shortest number accepted, so "call 1" isn't a number but short codes like "470" are. */
    const val MIN_DIGITS = 3

    /** Full numbers go to WhatsApp; shorter ones (short codes) can only get an SMS. */
    const val WHATSAPP_MIN_DIGITS = 10

    fun parse(said: String): String? {
        val words = said.lowercase().replace(Regex("[-().]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val out = StringBuilder()
        var plus = false
        var repeat = 1
        for (w in words) {
            when {
                w == "plus" && out.isEmpty() -> plus = true
                w in REPEAT -> repeat = REPEAT.getValue(w)
                w.all { it.isDigit() || it == '+' } && w.any { it.isDigit() } -> {
                    if (w.startsWith("+") && out.isEmpty()) plus = true
                    val d = w.filter { it.isDigit() }
                    // "double 4" repeats one digit; "double 44" is just 44.
                    out.append(if (d.length == 1) d.repeat(repeat) else d)
                    repeat = 1
                }
                w in DIGIT_WORDS -> {
                    out.append(DIGIT_WORDS.getValue(w).toString().repeat(repeat))
                    repeat = 1
                }
                else -> return null // a word that isn't part of a number: it's a name
            }
        }
        if (out.length < MIN_DIGITS) return null
        return (if (plus) "+" else "") + out
    }

    /** For WhatsApp links: digits only, Nigerian local "0803..." becomes "234803...". */
    fun international(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.startsWith("0") && d.length == 11) "234" + d.substring(1) else d
    }

    /** "470" -> "4 7 0", so the voice reads digits instead of "four hundred and seventy". */
    fun spoken(number: String): String = number.filter { it.isDigit() }.toList().joinToString(" ")
}
