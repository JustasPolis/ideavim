package com.maddyhome.idea.vim.extension.chatcopy

import java.awt.Point
import java.awt.Rectangle
import java.text.BreakIterator
import java.util.Locale
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.text.StyleConstants
import javax.swing.text.Utilities
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

internal data class ChatPosition(val part: Int, val offset: Int) : Comparable<ChatPosition> {
  override fun compareTo(other: ChatPosition): Int = compareValuesBy(this, other, ChatPosition::part, ChatPosition::offset)
}

internal class ChatTextPart(
  val component: JTextComponent,
  val root: javax.swing.JComponent,
  private val nativeOffsetAt: (Point) -> Int = { component.viewToModel2D(it) },
) {
  val text: String by lazy { component.document.getText(0, component.document.length) }
  private val characterBoundaries by lazy {
    BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(this@ChatTextPart.text) }
  }
  val lastOffset: Int get() = (text.length - 1).coerceAtLeast(0)
  val firstOffset: Int by lazy {
    (0..lastOffset).firstOrNull { findPoint(it) != null }
      ?: throw ChatCopyUnavailable("This chat text has no navigable character positions")
  }

  fun bounds(offset: Int): Rectangle = SwingUtilities.convertRectangle(
    component, requireNotNull(component.modelToView2D(offset)).bounds, root,
  )

  fun cursorBounds(offset: Int): Rectangle {
    val start = bounds(offset)
    val end = bounds(characterEnd(offset))
    return if (end.y == start.y && end.x != start.x) {
      Rectangle(minOf(start.x, end.x), start.y, kotlin.math.abs(end.x - start.x), start.height)
    } else {
      Rectangle(start.x, start.y, component.getFontMetrics(component.font).charWidth(' ').coerceAtLeast(1), start.height)
    }
  }

  fun offsetAt(point: Point): Int =
    nativeOffsetAt(SwingUtilities.convertPoint(root, point, component)).coerceIn(firstOffset, lastOffset)

  fun pointAt(offset: Int): Point =
    findPoint(offset) ?: throw ChatCopyUnavailable("Air text layout changed; character position is unavailable")

  private fun findPoint(offset: Int): Point? {
    val bounds = component.modelToView2D(offset) ?: return null
    val midpoint = (bounds.y + bounds.height / 2).toInt()
    for (adjustment in intArrayOf(0, 1, -1, 2, -2, 3, -3)) {
      val point = Point(bounds.x.toInt() + adjustment, midpoint)
      if (nativeOffsetAt(point) == offset) return SwingUtilities.convertPoint(component, point, root)
    }
    return null
  }

  fun horizontal(offset: Int, direction: Int): Int {
    return if (direction == SwingConstants.WEST) characterBoundaries.preceding(offset) else characterBoundaries.following(offset)
  }

  fun vertical(offset: Int, upwards: Boolean, rootX: Int): Int {
    val localX = SwingUtilities.convertPoint(root, Point(rootX, 0), component).x.toFloat()
    var next = if (upwards) Utilities.getPositionAbove(component, offset, localX)
    else Utilities.getPositionBelow(component, offset, localX)
    var skipped: MutableSet<Int>? = null
    while (next in firstOffset..lastOffset) {
      val bounds = component.modelToView2D(next)
      val element = (component.document as? HTMLDocument)?.getCharacterElement(next)
      val rule = element?.attributes?.getAttribute(StyleConstants.NameAttribute) == HTML.Tag.HR
      if (bounds != null && bounds.height > 0 && !rule) return next
      if (skipped == null) skipped = HashSet()
      if (!skipped.add(next)) return -1
      next = if (upwards) Utilities.getPositionAbove(component, next, localX)
      else Utilities.getPositionBelow(component, next, localX)
    }
    return next
  }

  fun rowStart(offset: Int): Int = Utilities.getRowStart(component, offset).coerceIn(firstOffset, lastOffset)
  fun rowEnd(offset: Int): Int = Utilities.getRowEnd(component, offset).coerceIn(firstOffset, lastOffset)
  fun characterEnd(offset: Int): Int = horizontal(offset, SwingConstants.EAST).let {
    if (it < 0) text.length else it
  }
}

internal interface ChatTranscript {
  val parts: List<ChatTextPart>
  val pageHeight: Int
  fun select(start: ChatPosition, exclusiveEnd: ChatPosition)
  fun clearSelection()
  fun selectedText(): String?
  fun reveal(position: ChatPosition)
}

internal class ChatCopyController(
  val transcript: ChatTranscript,
  private val copy: (String) -> Unit,
  private val exit: () -> Unit,
) {
  var cursor = ChatPosition(transcript.parts.lastIndex, transcript.parts.last().lastOffset)
    private set
  var anchor: ChatPosition? = null
    private set
  var linewise = false
    private set
  private var count = ""
  private var pendingTop = false
  private var desiredX: Int? = null
  private var lastQuery = ""
  private var searchForward = true
  private var selectionApplied = false

  val status: String get() = if (anchor == null) "CHAT COPY" else if (linewise) "CHAT VISUAL LINE" else "CHAT VISUAL"

  fun character(character: Char) {
    if (character in '1'..'9' || character == '0' && count.isNotEmpty()) {
      if (count.length < 4) count += character
      return
    }
    val repetitions = count.toIntOrNull()?.coerceIn(1, 9999) ?: 1
    count = ""
    if (pendingTop) {
      pendingTop = false
      if (character == 'g') {
        cursor = ChatPosition(0, transcript.parts.first().firstOffset)
        changed()
        return
      }
    }
    when (character) {
      'g' -> pendingTop = true
      'G' -> { cursor = ChatPosition(transcript.parts.lastIndex, transcript.parts.last().lastOffset); changed() }
      'h', 'l' -> { repeat(repetitions) { horizontal(character == 'h') }; changed() }
      'j', 'k' -> { repeat(repetitions) { vertical(character == 'k') }; changed(resetColumn = false) }
      '0', '$' -> {
        val part = transcript.parts[cursor.part]
        cursor = cursor.copy(offset = if (character == '0') part.rowStart(cursor.offset) else part.rowEnd(cursor.offset))
        changed()
      }
      'w', 'b' -> { repeat(repetitions) { word(character == 'b') }; changed() }
      'v', 'V' -> {
        if (anchor != null && linewise == (character == 'V')) {
          anchor = null
        } else {
          if (anchor == null) anchor = cursor
          linewise = character == 'V'
        }
        changed()
      }
      'o' -> anchor?.let { original -> anchor = cursor; cursor = original; changed() }
      'y' -> transcript.selectedText()?.takeIf { it.isNotEmpty() }?.let { copy(it); exit() }
      'q' -> exit()
      'n' -> search(lastQuery, searchForward, remember = false)
      'N' -> search(lastQuery, !searchForward, remember = false)
    }
  }

  fun escape() {
    if (count.isNotEmpty() || pendingTop) {
      count = ""
      pendingTop = false
    } else if (anchor != null) {
      anchor = null
      changed()
    } else {
      exit()
    }
  }

  fun page(fraction: Double) {
    val bounds = transcript.parts[cursor.part].bounds(cursor.offset)
    val target = Point(bounds.x, bounds.y + (transcript.pageHeight * fraction).toInt())
    val partIndex = transcript.parts.indices.minBy { index ->
      val part = transcript.parts[index]
      val first = part.bounds(part.firstOffset).y
      val last = part.bounds(part.lastOffset).let { it.y + it.height }
      when {
        target.y < first -> first - target.y
        target.y > last -> target.y - last
        else -> 0
      }
    }
    cursor = ChatPosition(partIndex, transcript.parts[partIndex].offsetAt(target))
    changed()
  }

  fun search(query: String, forward: Boolean, remember: Boolean = true): Boolean {
    if (query.isEmpty()) return false
    if (remember) {
      lastQuery = query
      searchForward = forward
    }
    val indices = if (forward) transcript.parts.indices.toList() else transcript.parts.indices.reversed()
    val ordered = indices.dropWhile { it != cursor.part } + indices.takeWhile { it != cursor.part } + cursor.part
    for ((iteration, index) in ordered.withIndex()) {
      val text = transcript.parts[index].text
      val start = if (iteration == 0) cursor.offset + if (forward) 1 else -1
      else if (forward) transcript.parts[index].firstOffset else text.lastIndex
      val found = if (forward) text.indexOf(query, start)
      else if (start < 0) -1 else text.lastIndexOf(query, start)
      if (found >= transcript.parts[index].firstOffset) {
        cursor = ChatPosition(index, found)
        changed()
        return true
      }
    }
    return false
  }

  fun refreshSelection() {
    if (selectionApplied) {
      transcript.clearSelection()
      selectionApplied = false
    }
    anchor?.let { original ->
      val start = minOf(original, cursor)
      val end = maxOf(original, cursor)
      val startPart = transcript.parts[start.part]
      val endPart = transcript.parts[end.part]
      transcript.select(
        start.copy(offset = if (linewise) startPart.rowStart(start.offset) else start.offset),
        end.copy(offset = if (linewise) (endPart.rowEnd(end.offset) + 1).coerceAtMost(endPart.text.length)
        else endPart.characterEnd(end.offset)),
      )
      selectionApplied = true
    }
  }

  private fun horizontal(left: Boolean) {
    val part = transcript.parts[cursor.part]
    val next = part.horizontal(cursor.offset, if (left) SwingConstants.WEST else SwingConstants.EAST)
    cursor = when {
      next in part.firstOffset..part.lastOffset && next != cursor.offset -> cursor.copy(offset = next)
      left && cursor.part > 0 -> ChatPosition(cursor.part - 1, transcript.parts[cursor.part - 1].lastOffset)
      !left && cursor.part < transcript.parts.lastIndex ->
        ChatPosition(cursor.part + 1, transcript.parts[cursor.part + 1].firstOffset)
      else -> cursor
    }
  }

  private fun vertical(upwards: Boolean) {
    val part = transcript.parts[cursor.part]
    val currentBounds = part.bounds(cursor.offset)
    val rootX = desiredX ?: currentBounds.x
    val next = part.vertical(cursor.offset, upwards, rootX)
    val nextBounds = if (next in part.firstOffset..part.lastOffset) part.bounds(next) else null
    val movesVertically = nextBounds != null &&
      (if (upwards) nextBounds.y < currentBounds.y else nextBounds.y > currentBounds.y)
    if (movesVertically) {
      cursor = cursor.copy(offset = next)
    } else {
      val nextIndex = cursor.part + if (upwards) -1 else 1
      if (nextIndex in transcript.parts.indices) {
        val destination = transcript.parts[nextIndex]
        val bounds = destination.bounds(if (upwards) destination.lastOffset else destination.firstOffset)
        cursor = ChatPosition(nextIndex, destination.offsetAt(Point(rootX, bounds.y + bounds.height / 2)))
      }
    }
    desiredX = rootX
  }

  private fun word(backwards: Boolean) {
    val part = transcript.parts[cursor.part]
    val offset = try {
      if (backwards) Utilities.getPreviousWord(part.component, cursor.offset)
      else Utilities.getNextWord(part.component, cursor.offset)
    } catch (_: javax.swing.text.BadLocationException) {
      -1
    }
    if (offset in part.firstOffset..part.lastOffset) {
      cursor = cursor.copy(offset = offset)
    } else {
      val nextIndex = cursor.part + if (backwards) -1 else 1
      if (nextIndex in transcript.parts.indices) {
        val destination = transcript.parts[nextIndex]
        cursor = ChatPosition(nextIndex, if (backwards) destination.lastOffset else destination.firstOffset)
      }
    }
  }

  private fun changed(resetColumn: Boolean = true) {
    if (resetColumn) desiredX = null
    refreshSelection()
    transcript.reveal(cursor)
  }
}
