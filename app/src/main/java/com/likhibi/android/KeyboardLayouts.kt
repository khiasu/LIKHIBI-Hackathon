package com.likhibi.android

/** A long-press alternate: a character to type, or a keyboard action shown as an icon. */
data class Alt(val label: String, val code: Int = label.codePointAt(0), val icon: Int = 0, val description: String = label)

enum class KeyType { CHAR, FUNCTION, SHIFT, DELETE, SPACE, ENTER }

/**
 * One key. [width] is in key units: a letter key is 1 and every row is 10 units wide, so
 * letters line up across rows the way Gboard's do.
 */
class Key(
    val label: String,
    val code: Int,
    val width: Float = 1f,
    val type: KeyType = KeyType.CHAR,
    val icon: Int = 0,
    val hint: String? = null,
    val alternates: List<Alt> = emptyList(),
    val description: String = label,
) {
    val upper: String = label.uppercase()

    // Where the key is drawn, and the larger area that counts as touching it (gaps included).
    var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
    var hitLeft = 0f; var hitTop = 0f; var hitRight = 0f; var hitBottom = 0f

    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** [inset] is blank space at each end of the row, in key units (the a-row is inset half a key). */
class Row(val keys: List<Key>, val inset: Float = 0f)

data class LayoutOptions(
    val numberRow: Boolean,
    val emojiKey: Boolean,
    val longPressSymbols: Boolean,
    val enterIcon: Int,
    val enterDescription: String,
    val icons: Icons,
) {
    /** Drawable ids, passed in so this file stays free of Android resources (and unit-testable). */
    data class Icons(
        val shift: Int, val delete: Int, val emoji: Int, val settings: Int, val oneHanded: Int, val clipboard: Int,
    )
}

object KeyboardLayouts {
    const val CODE_SHIFT = -1
    const val CODE_SYMBOLS = -2
    const val CODE_EXTRA_SYMBOLS = -3
    const val CODE_SYMBOLS_BACK = -4
    const val CODE_DELETE = -5
    const val CODE_EMOJI = -10
    const val CODE_SWITCH_IME = -11
    const val CODE_SETTINGS = -12
    const val CODE_ONE_HANDED = -13
    const val CODE_CLIPBOARD = -14
    const val CODE_LETTERS = -15
    const val CODE_ENTER = '\n'.code
    const val CODE_SPACE = ' '.code

    private val DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val ROW1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val ROW2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val ROW3 = listOf("z", "x", "c", "v", "b", "n", "m")
    // Gboard's long-press symbols: the a-row and z-row carry the symbols page's second and third rows.
    private val ROW2_SYMBOLS = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")")
    private val ROW3_SYMBOLS = listOf("*", "\"", "'", ":", ";", "!", "?")
    private val ACCENTS = mapOf(
        "a" to listOf("à", "á", "â", "ä", "ã", "å", "ā"),
        "e" to listOf("é", "è", "ê", "ë", "ē"),
        "i" to listOf("í", "ì", "î", "ï"),
        "o" to listOf("ó", "ò", "ô", "ö", "õ", "ō"),
        "u" to listOf("ú", "ù", "û", "ü", "ū"),
        "n" to listOf("ñ"),
        "c" to listOf("ç"),
        "y" to listOf("ý"),
    )
    private val PERIOD_ALTERNATES = listOf("?", "!", ",", "'", "\"", ":", ";", "-", "@", "#", "/")

    fun letters(o: LayoutOptions): List<Row> = buildList {
        if (o.numberRow) add(Row(DIGITS.map { Key(it, it.code()) }))
        add(Row(ROW1.mapIndexed { i, l ->
            val digit = DIGITS[i].takeIf { o.longPressSymbols && !o.numberRow }
            Key(l, l.code(), hint = digit, alternates = alts(listOfNotNull(digit) + ACCENTS[l].orEmpty()))
        }))
        add(Row(ROW2.mapIndexed { i, l ->
            val symbol = ROW2_SYMBOLS[i].takeIf { o.longPressSymbols }
            Key(l, l.code(), hint = symbol, alternates = alts(listOfNotNull(symbol) + ACCENTS[l].orEmpty()))
        }, inset = 0.5f))
        add(Row(listOf(Key("", CODE_SHIFT, 1.5f, KeyType.SHIFT, icon = o.icons.shift, description = "Shift")) +
            ROW3.mapIndexed { i, l ->
                val symbol = ROW3_SYMBOLS[i].takeIf { o.longPressSymbols }
                Key(l, l.code(), hint = symbol, alternates = alts(listOfNotNull(symbol) + ACCENTS[l].orEmpty()))
            } + delete(o)))
        add(bottomRow(o, "?123", "Symbols"))
    }

    fun symbols(o: LayoutOptions): List<Row> = listOf(
        Row(DIGITS.map { Key(it, it.code()) }),
        Row(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { Key(it, it.code()) }),
        Row(listOf(Key("=\\<", CODE_EXTRA_SYMBOLS, 1.5f, KeyType.FUNCTION, description = "More symbols")) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(it, it.code()) } + delete(o)),
        bottomRow(o, "ABC", "Letters"),
    )

    fun extraSymbols(o: LayoutOptions): List<Row> = listOf(
        Row(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { Key(it, it.code()) }),
        Row(listOf("£", "¥", "€", "¢", "^", "°", "=", "{", "}", "\\").map { Key(it, it.code()) }),
        Row(listOf(Key("?123", CODE_SYMBOLS_BACK, 1.5f, KeyType.FUNCTION, description = "Symbols")) +
            listOf("%", "©", "®", "<", ">", "[", "]").map { Key(it, it.code()) } + delete(o)),
        bottomRow(o, "ABC", "Letters"),
    )

    /** Letters for typing into the emoji search box: no number row, no long-press. */
    fun search(o: LayoutOptions): List<Row> = listOf(
        Row(ROW1.map { Key(it, it.code()) }),
        Row(ROW2.map { Key(it, it.code()) }, inset = 0.5f),
        Row(ROW3.map { Key(it, it.code()) } + delete(o)),
        Row(listOf(
            Key("ABC", CODE_LETTERS, 1.5f, KeyType.FUNCTION, description = "Back to emoji"),
            Key("Likhibi", CODE_SPACE, 7f, KeyType.SPACE, description = "Space"),
            Key("", CODE_ENTER, 1.5f, KeyType.ENTER, icon = o.enterIcon, description = "Done"),
        )),
    )

    private fun delete(o: LayoutOptions) = Key("", CODE_DELETE, 1.5f, KeyType.DELETE, icon = o.icons.delete, description = "Delete")

    /** ?123, comma (long-press: tools), emoji, space, period, enter, as on Gboard. */
    private fun bottomRow(o: LayoutOptions, modeLabel: String, modeDescription: String): Row = Row(buildList {
        add(Key(modeLabel, CODE_SYMBOLS, 1.5f, KeyType.FUNCTION, description = modeDescription))
        add(Key(",", ','.code, alternates = listOf(
            Alt("", CODE_SETTINGS, o.icons.settings, "Keyboard settings"),
            Alt("", CODE_ONE_HANDED, o.icons.oneHanded, "One-handed mode"),
            Alt("", CODE_CLIPBOARD, o.icons.clipboard, "Clipboard"),
            Alt("", CODE_EMOJI, o.icons.emoji, "Emoji"),
        ).let { tools -> if (o.emojiKey) tools.dropLast(1) else tools }))
        if (o.emojiKey) add(Key("", CODE_EMOJI, 1f, KeyType.FUNCTION, icon = o.icons.emoji, description = "Emoji"))
        add(Key("Likhibi", CODE_SPACE, if (o.emojiKey) 4f else 5f, KeyType.SPACE, description = "Space"))
        add(Key(".", '.'.code, alternates = alts(PERIOD_ALTERNATES)))
        add(Key("", CODE_ENTER, 1.5f, KeyType.ENTER, icon = o.enterIcon, description = o.enterDescription))
    })

    private fun alts(labels: List<String>) = labels.map { Alt(it) }
    private fun String.code() = codePointAt(0)

    /**
     * Places every key. Keys are drawn with gaps between them, but their touch areas meet
     * in the middle of each gap and run to the keyboard's edges, so no touch is ever lost.
     */
    fun place(rows: List<Row>, width: Float, keyHeight: Float, rowGap: Float, keyGap: Float, side: Float, top: Float, bottom: Float): Float {
        val unit = (width - 2 * side) / 10f
        var y = top
        rows.forEachIndexed { r, row ->
            val units = row.keys.sumOf { it.width.toDouble() }.toFloat() + 2 * row.inset
            var x = side + row.inset * unit + (10f - units) / 2 * unit
            val rowTop = y
            val rowBottom = y + keyHeight
            row.keys.forEachIndexed { k, key ->
                val w = key.width * unit
                key.left = x + keyGap / 2
                key.right = x + w - keyGap / 2
                key.top = rowTop
                key.bottom = rowBottom
                key.hitLeft = if (k == 0) 0f else x
                key.hitRight = if (k == row.keys.lastIndex) width else x + w
                key.hitTop = if (r == 0) 0f else rowTop - rowGap / 2
                key.hitBottom = if (r == rows.lastIndex) Float.MAX_VALUE else rowBottom + rowGap / 2
                x += w
            }
            y = rowBottom + if (r == rows.lastIndex) bottom else rowGap
        }
        return y
    }

    /** The key under a touch, or the nearest one if the touch is outside every key's area. */
    fun keyAt(rows: List<Row>, x: Float, y: Float): Key? {
        var best: Key? = null
        var bestDistance = Float.MAX_VALUE
        for (row in rows) for (key in row.keys) {
            if (x >= key.hitLeft && x < key.hitRight && y >= key.hitTop && y < key.hitBottom) return key
            val dx = x - key.centerX
            val dy = y - key.centerY
            val d = dx * dx + dy * dy
            if (d < bestDistance) {
                bestDistance = d
                best = key
            }
        }
        return best
    }
}
