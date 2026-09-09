package com.elicode.app.ui.components

/**
 * Minimal VT100/xterm screen emulator (pure Kotlin, no Android/Compose
 * dependencies so JVM unit tests can drive it).
 *
 * Covers what fullscreen TUIs (opencode/bubbletea, vim, htop) need:
 * printable text, scrolling, cursor movement/positioning, erase, SGR
 * colors (16 + 256 + truecolor), alternate screen, cursor visibility,
 * line-wrap mode, save/restore cursor and scroll margins. Unknown
 * sequences are skipped, never crash.
 *
 * Colors are ARGB Ints; [FG_DEFAULT]/[BG_DEFAULT] (-1) mean "use the UI
 * theme default" so the view adapts to light/dark themes.
 */
class VtEmulator(
    var cols: Int,
    var rows: Int,
    private val scrollbackMax: Int = 1500
) {
    companion object {
        const val FG_DEFAULT = -1
        const val BG_DEFAULT = -1

        // xterm 16-color palette (0-7 normal, 8-15 bright).
        private val BASE16 = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
        )

        fun palette256(n: Int): Int {
            if (n < 0) return FG_DEFAULT
            if (n < 8) return BASE16[n]
            if (n < 16) return BASE16[n]
            if (n < 232) {
                val i = n - 16
                val r = i / 36
                val g = (i % 36) / 6
                val b = i % 6
                fun v(c: Int) = if (c == 0) 0 else 55 + c * 40
                return (0xFF shl 24) or (v(r) shl 16) or (v(g) shl 8) or v(b)
            }
            val g = 8 + (n - 232) * 10
            return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    data class Cell(
        var ch: Char = ' ',
        var fg: Int = FG_DEFAULT,
        var bg: Int = BG_DEFAULT,
        var bold: Boolean = false,
        var dim: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var reverse: Boolean = false
    ) {
        fun copy() = Cell(ch, fg, bg, bold, dim, italic, underline, reverse)
    }

    data class Run(
        val text: String,
        val fg: Int,
        val bg: Int,
        val bold: Boolean,
        val underline: Boolean
    )

    data class Cursor(val col: Int, val row: Int, val visible: Boolean)

    /** Bumped on every flush so Compose can recompose cheaply. */
    var version: Long = 0L
        private set

    private var grid: Array<Array<Cell>> = freshGrid(rows)
    private var altGrid: Array<Array<Cell>>? = null
    private val scrollback = ArrayDeque<Array<Cell>>()

    private var cx = 0
    private var cy = 0
    private var cursorVisible = true
    private var wrapMode = true
    private var topMargin = 0
    private var bottomMargin = rows - 1

    // Active SGR attributes for new cells.
    private var curFg = FG_DEFAULT
    private var curBg = BG_DEFAULT
    private var curBold = false
    private var curDim = false
    private var curItalic = false
    private var curUnderline = false
    private var curReverse = false

    private var savedCx = 0
    private var savedCy = 0

    // ---- parser state ----
    private var state = 0 // 0 ground, 1 esc, 2 csi, 3 osc, 4 charset
    private val paramBuf = StringBuilder()
    private var privateMode = false

    private fun freshGrid(r: Int): Array<Array<Cell>> =
        Array(r) { Array(cols) { Cell() } }

    private fun active(): Array<Array<Cell>> = altGrid ?: grid

    val usingAltScreen: Boolean get() = altGrid != null

    fun process(text: String) {
        for (ch in text) feed(ch)
        version++
    }

    // ---------------- feed ----------------

    private fun feed(ch: Char) {
        when (state) {
            0 -> ground(ch)
            1 -> esc(ch)
            2 -> csi(ch)
            3 -> osc(ch)
            4 -> state = 0 // charset introducer consumed
        }
    }

    private fun ground(ch: Char) {
        when (ch) {
            '\u001B' -> { state = 1 }
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\r' -> { cx = 0 }
            '\b' -> { if (cx > 0) cx-- }
            '\t' -> { cx = ((cx / 8) + 1) * 8; if (cx >= cols) { cx = cols - 1 } }
            '\u0007' -> { /* bell: ignore */ }
            '\u0000' -> { /* nul: ignore */ }
            else -> if (!ch.isISOControl()) put(ch)
        }
    }

    private fun esc(ch: Char) {
        when (ch) {
            '[' -> { state = 2; paramBuf.clear(); privateMode = false }
            ']' -> { state = 3 }
            '(','#',')','*' -> { state = 4 }
            '7' -> { savedCx = cx; savedCy = cy; state = 0 }
            '8' -> { cx = savedCx; cy = savedCy; clampCursor(); state = 0 }
            'D' -> { lineFeed(); state = 0 }
            'M' -> { reverseIndex(); state = 0 }
            'E' -> { cx = 0; lineFeed(); state = 0 }
            'c' -> { reset(); state = 0 }
            '=', '>' -> { state = 0 } // keypad mode: ignore
            else -> { state = 0 } // unknown: drop
        }
    }

    private fun csi(ch: Char) {
        if (ch == '?' && paramBuf.isEmpty()) {
            privateMode = true
            return
        }
        if (ch.isDigit() || ch == ';' || ch == ':' || ch == '$' ||
            ch == '"' || ch == ' ' || ch == '\'' || ch == '#' || ch == '%'
        ) {
            if (paramBuf.length < 64) paramBuf.append(ch)
            return
        }
        if (ch in '@'..'~') {
            dispatchCsi(ch)
            state = 0
        } else {
            state = 0 // malformed: drop
        }
    }

    private fun osc(ch: Char) {
        // OSC ... terminated by BEL or ESC \. We only need to skip it
        // (window titles, hyperlinks, clipboard). ESC inside OSC starts
        // a new escape: the ST backslash is consumed as a no-op.
        if (ch == '\u0007') state = 0
        else if (ch == '\u001B') state = 1
    }

    private fun params(): List<Int> {
        if (paramBuf.isEmpty()) return emptyList()
        return paramBuf.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private fun p(i: Int, default: Int): Int {
        val ps = params()
        if (i >= ps.size) return default
        val v = ps[i]
        return if (v == 0) default else v
    }

    private fun dispatchCsi(final: Char) {
        val g = active()
        when (final) {
            'A' -> { cy -= p(0, 1); clampCursor() } // up
            'B', 'e' -> { cy += p(0, 1); clampCursor() } // down
            'C', 'a' -> { cx += p(0, 1); clampCursor() } // forward
            'D' -> { cx -= p(0, 1); clampCursor() } // back
            'E' -> { cx = 0; cy += p(0, 1); clampCursor() }
            'F' -> { cx = 0; cy -= p(0, 1); clampCursor() }
            'G', '`' -> { cx = p(0, 1) - 1; clampCursor() } // column
            'd' -> { cy = p(0, 1) - 1; clampCursor() } // row
            'H', 'f' -> { // cursor position
                val ps = params()
                cy = (if (ps.isNotEmpty() && ps[0] != 0) ps[0] else 1) - 1
                cx = (if (ps.size > 1 && ps[1] != 0) ps[1] else 1) - 1
                clampCursor()
            }
            'J' -> when (p(0, 0)) { // erase display
                0 -> { eraseRange(cx, cy, cols - 1, rows - 1) }
                1 -> { eraseRange(0, 0, cx, cy) }
                2, 3 -> { eraseAll(g) }
            }
            'K' -> when (p(0, 0)) { // erase line
                0 -> { eraseRange(cx, cy, cols - 1, cy) }
                1 -> { eraseRange(0, cy, cx, cy) }
                2 -> { eraseRange(0, cy, cols - 1, cy) }
            }
            'X' -> { // erase chars
                val n = p(0, 1)
                for (i in 0 until n) {
                    val x = cx + i
                    if (x < cols) g[cy][x] = Cell()
                }
            }
            'L' -> insertLines(p(0, 1)) // insert lines
            'M' -> deleteLines(p(0, 1)) // delete lines
            'P' -> { // delete chars
                val n = p(0, 1)
                val row = g[cy]
                for (x in cx until cols) row[x] = if (x + n < cols) row[x + n].copy() else Cell()
            }
            '@' -> { // insert chars
                val n = p(0, 1)
                val row = g[cy]
                for (x in cols - 1 downTo 0) row[x] = if (x - n >= cx) row[x - n].copy() else Cell()
            }
            'S' -> repeat(p(0, 1)) { scrollUp() }
            'T' -> repeat(p(0, 1)) { scrollDown() }
            'm' -> sgr(params())
            's' -> { savedCx = cx; savedCy = cy }
            'u' -> { cx = savedCx; cy = savedCy; clampCursor() }
            'r' -> { // scroll margins
                val ps = params()
                topMargin = ((if (ps.isNotEmpty() && ps[0] != 0) ps[0] else 1) - 1).coerceIn(0, rows - 1)
                bottomMargin = ((if (ps.size > 1 && ps[1] != 0) ps[1] else rows) - 1).coerceIn(topMargin, rows - 1)
                cx = 0; cy = topMargin
            }
            'h', 'l' -> { // modes
                val on = final == 'h'
                for (m in params()) {
                    when {
                        privateMode && m == 25 -> cursorVisible = on
                        privateMode && m == 1049 -> if (on) enterAlt() else exitAlt()
                        privateMode && m == 1047 -> if (on) enterAlt() else exitAlt()
                        privateMode && m == 7 -> wrapMode = on
                        privateMode && m == 2004 -> { /* bracketed paste: ignore */ }
                        !privateMode && m == 4 -> { /* insert mode: ignore */ }
                    }
                }
            }
            'c' -> { /* device attrs query: app answers; nothing to do */ }
            'n' -> { /* status report query: ignore */ }
            't' -> { /* window ops: ignore */ }
            'q' -> { /* cursor style: ignore */ }
            else -> { /* unknown final: drop */ }
        }
    }

    // ---------------- actions ----------------

    private fun put(ch: Char) {
        val g = active()
        if (cx >= cols) {
            if (!wrapMode) {
                cx = cols - 1
            } else {
                cx = 0
                lineFeed()
            }
        }
        if (cy >= rows) {
            scrollUp()
            cy = rows - 1
        }
        g[cy][cx] = Cell(ch, curFg, curBg, curBold, curDim, curItalic, curUnderline, curReverse)
        cx++
    }

    private fun lineFeed() {
        cy++
        if (cy > bottomMargin) {
            cy = bottomMargin
            scrollUp()
        }
    }

    private fun reverseIndex() {
        if (cy == topMargin) scrollDown() else { cy--; clampCursor() }
    }

    private fun scrollUp() {
        val g = active()
        if (altGrid == null && topMargin == 0 && bottomMargin == rows - 1) {
            scrollback.addLast(g[0].map { it.copy() }.toTypedArray())
            if (scrollback.size > scrollbackMax) scrollback.removeFirst()
        }
        for (r in topMargin until bottomMargin) g[r] = g[r + 1]
        g[bottomMargin] = Array(cols) { Cell() }
    }

    private fun scrollDown() {
        val g = active()
        for (r in bottomMargin downTo topMargin + 1) g[r] = g[r - 1]
        g[topMargin] = Array(cols) { Cell() }
    }

    private fun insertLines(n: Int) {
        val g = active()
        repeat(n.coerceAtMost(bottomMargin - cy + 1).coerceAtLeast(0)) {
            for (r in bottomMargin downTo cy + 1) g[r] = g[r - 1]
            g[cy] = Array(cols) { Cell() }
        }
    }

    private fun deleteLines(n: Int) {
        val g = active()
        repeat(n.coerceAtMost(bottomMargin - cy + 1).coerceAtLeast(0)) {
            for (r in cy until bottomMargin) g[r] = g[r + 1]
            g[bottomMargin] = Array(cols) { Cell() }
        }
    }

    private fun eraseRange(x0: Int, y0: Int, x1: Int, y1: Int) {
        val g = active()
        for (y in y0..y1) {
            if (y !in 0 until rows) continue
            for (x in x0..x1) {
                if (x in 0 until cols) g[y][x] = Cell()
            }
        }
    }

    private fun eraseAll(g: Array<Array<Cell>>) {
        for (r in 0 until rows) for (c in 0 until cols) g[r][c] = Cell()
        cx = 0
        cy = 0
    }

    private fun enterAlt() {
        if (altGrid == null) {
            altGrid = freshGrid(rows)
            cx = 0; cy = 0
        }
    }

    private fun exitAlt() {
        altGrid = null
        clampCursor()
    }

    private fun clampCursor() {
        cx = cx.coerceIn(0, cols - 1)
        cy = cy.coerceIn(0, rows - 1)
    }

    private fun sgr(ps: List<Int>) {
        if (ps.isEmpty()) {
            resetAttrs()
            return
        }
        var i = 0
        while (i < ps.size) {
            when (val v = ps[i]) {
                0 -> resetAttrs()
                1 -> curBold = true
                2 -> curDim = true
                3 -> curItalic = true
                4 -> curUnderline = true
                7 -> curReverse = true
                22 -> { curBold = false; curDim = false }
                23 -> curItalic = false
                24 -> curUnderline = false
                27 -> curReverse = false
                30, 31, 32, 33, 34, 35, 36, 37 -> curFg = BASE16[v - 30]
                39 -> curFg = FG_DEFAULT
                40, 41, 42, 43, 44, 45, 46, 47 -> curBg = BASE16[v - 40]
                49 -> curBg = BG_DEFAULT
                90, 91, 92, 93, 94, 95, 96, 97 -> curFg = BASE16[v - 90 + 8]
                100, 101, 102, 103, 104, 105, 106, 107 -> curBg = BASE16[v - 100 + 8]
                38, 48 -> {
                    val isFg = v == 38
                    val mode = ps.getOrNull(i + 1) ?: 0
                    if (mode == 5) {
                        val n = ps.getOrNull(i + 2) ?: 0
                        if (isFg) curFg = palette256(n) else curBg = palette256(n)
                        i += 2
                    } else if (mode == 2) {
                        val r = ps.getOrNull(i + 2) ?: 0
                        val g = ps.getOrNull(i + 3) ?: 0
                        val b = ps.getOrNull(i + 4) ?: 0
                        val argb = (0xFF shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
                        if (isFg) curFg = argb else curBg = argb
                        i += 4
                    } else {
                        i += 1
                    }
                }
                else -> { /* unsupported attr: ignore */ }
            }
            i++
        }
    }

    private fun resetAttrs() {
        curFg = FG_DEFAULT
        curBg = BG_DEFAULT
        curBold = false
        curDim = false
        curItalic = false
        curUnderline = false
        curReverse = false
    }

    private fun reset() {
        eraseAll(active())
        if (altGrid != null) {
            altGrid = freshGrid(rows)
        }
        resetAttrs()
        cursorVisible = true
        wrapMode = true
        topMargin = 0
        bottomMargin = rows - 1
    }

    fun resize(c: Int, r: Int) {
        if (c == cols && r == rows) return
        cols = c.coerceAtLeast(1)
        rows = r.coerceAtLeast(1)
        grid = freshGrid(rows)
        altGrid = null
        cx = 0; cy = 0
        topMargin = 0
        bottomMargin = rows - 1
        version++
    }

    // ---------------- render ----------------

    fun cursor(): Cursor = Cursor(cx.coerceIn(0, cols - 1), cy.coerceIn(0, rows - 1), cursorVisible)

    /**
     * Visible rows as style runs, with the cursor cell rendered as
     * reverse video when [cursorVisible]. Trailing blank cells are
     * trimmed per row (empty row → single empty run).
     */
    fun renderedRows(): List<List<Run>> {
        val g = active()
        val cur = cursor()
        return (0 until rows).map { y ->
            val row = g[y]
            var end = cols
            while (end > 0 && row[end - 1].ch == ' ' && row[end - 1].bg == BG_DEFAULT) end--
            if (end == 0) return@map listOf(Run("", FG_DEFAULT, BG_DEFAULT, false, false))
            val runs = mutableListOf<Run>()
            val sb = StringBuilder()
            var rFg = Int.MIN_VALUE
            var rBg = Int.MIN_VALUE
            var rBold = false
            var rUl = false
            fun flush() {
                if (sb.isNotEmpty()) {
                    runs += Run(sb.toString(), rFg, rBg, rBold, rUl)
                    sb.clear()
                }
            }
            for (x in 0 until end) {
                val cell = row[x]
                var fg = cell.fg
                var bg = cell.bg
                if (cell.reverse || (cur.visible && cur.col == x && cur.row == y)) {
                    val rf = if (bg == BG_DEFAULT) 0xFF000000.toInt() else bg
                    val rb = if (fg == FG_DEFAULT) 0xFFFFFFFF.toInt() else fg
                    fg = rf
                    bg = rb
                }
                if (sb.isEmpty()) {
                    rFg = fg; rBg = bg; rBold = cell.bold; rUl = cell.underline
                }
                if (fg != rFg || bg != rBg || cell.bold != rBold || cell.underline != rUl) {
                    flush()
                    rFg = fg; rBg = bg; rBold = cell.bold; rUl = cell.underline
                }
                sb.append(cell.ch)
            }
            flush()
            runs
        }
    }

    /** Plain-text snapshot (tests, logs, accessibility). */
    fun plainText(): String =
        active().joinToString("\n") { row ->
            row.joinToString("") { it.ch.toString() }.trimEnd()
        }.trimEnd()
}
