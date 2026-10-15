package com.likhibi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutsTest {
    private val icons = LayoutOptions.Icons(1, 2, 3, 4, 5, 6)

    private fun options(numberRow: Boolean = false, emojiKey: Boolean = true) =
        LayoutOptions(numberRow, emojiKey, longPressSymbols = true, enterIcon = 7, enterDescription = "Enter", icons = icons)

    private fun placed(rows: List<Row>): Float = KeyboardLayouts.place(rows, 1080f, 120f, 26f, 16f, 8f, 16f, 20f)

    @Test
    fun everyRowIsTenUnitsWide() {
        for (layout in listOf(KeyboardLayouts.letters(options()), KeyboardLayouts.letters(options(numberRow = true, emojiKey = false)),
            KeyboardLayouts.symbols(options()), KeyboardLayouts.extraSymbols(options()), KeyboardLayouts.search(options()))) {
            for (row in layout) {
                val units = row.keys.sumOf { it.width.toDouble() } + 2 * row.inset
                assertTrue("row of ${row.keys.map { it.label }} is $units units", units <= 10.0001)
            }
        }
    }

    @Test
    fun touchesInTheGapsStillHitAKey() {
        val rows = KeyboardLayouts.letters(options())
        val height = placed(rows)
        var y = 0f
        while (y < height) {
            var x = 0f
            while (x < 1080f) {
                assertNotNull("no key at $x,$y", KeyboardLayouts.keyAt(rows, x, y))
                x += 3f
            }
            y += 3f
        }
    }

    @Test
    fun keyCentresHitTheirOwnKey() {
        val rows = KeyboardLayouts.letters(options(numberRow = true))
        placed(rows)
        for (row in rows) for (key in row.keys) assertSame(key, KeyboardLayouts.keyAt(rows, key.centerX, key.centerY))
    }

    @Test
    fun lettersLineUpLikeGboard() {
        val rows = KeyboardLayouts.letters(options())
        placed(rows)
        val q = rows[0].keys.first()
        val a = rows[1].keys.first()
        val w = rows[0].keys[1]
        // The a-row starts half a key in, so "a" sits between "q" and "w".
        assertEquals((q.centerX + w.centerX) / 2, a.centerX, 0.01f)
        // The gap left of "a" belongs to "a".
        assertSame(a, KeyboardLayouts.keyAt(rows, 1f, a.centerY))
    }

    @Test
    fun emojiKeyNarrowsTheSpaceBar() {
        val with = KeyboardLayouts.letters(options(emojiKey = true)).last().keys
        val without = KeyboardLayouts.letters(options(emojiKey = false)).last().keys
        assertEquals(4f, with.first { it.type == KeyType.SPACE }.width)
        assertEquals(5f, without.first { it.type == KeyType.SPACE }.width)
        assertTrue(with.any { it.code == KeyboardLayouts.CODE_EMOJI })
    }
}
