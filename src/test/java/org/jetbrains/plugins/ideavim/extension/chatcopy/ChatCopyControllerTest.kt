package org.jetbrains.plugins.ideavim.extension.chatcopy

import com.maddyhome.idea.vim.extension.chatcopy.AirTranscriptSurface
import com.maddyhome.idea.vim.extension.chatcopy.ChatCopyController
import com.maddyhome.idea.vim.extension.chatcopy.ChatCopyUnavailable
import com.maddyhome.idea.vim.extension.chatcopy.ChatPosition
import com.maddyhome.idea.vim.extension.chatcopy.ChatTextPart
import com.maddyhome.idea.vim.extension.chatcopy.ChatTranscript
import org.junit.jupiter.api.Test
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JEditorPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.SwingConstants
import javax.swing.text.PlainDocument
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatCopyControllerTest {
  @Test
  fun `ordinary navigation never clears or rebuilds native selection`() = onEdt {
    val fixture = Fixture()
    fixture.keys("ggjjkkhllwbgG")
    assertEquals(0, fixture.transcript.clearCalls)
    assertEquals(0, fixture.transcript.selectCalls)
  }

  @Test
  fun `a counted vertical motion reveals its final position only once`() = onEdt {
    val fixture = Fixture((0..12).joinToString("\n") { "row $it" }, "next")
    fixture.keys("gg")
    fixture.transcript.revealCalls = 0
    fixture.keys("8j")
    assertEquals(fixture.transcript.parts.first().text.indexOf("row 8"), fixture.controller.cursor.offset)
    assertEquals(1, fixture.transcript.revealCalls)
    assertEquals(0, fixture.transcript.clearCalls)
  }

  @Test
  fun `a counted visual motion updates native selection only once`() = onEdt {
    val fixture = Fixture()
    fixture.keys("ggv")
    fixture.transcript.revealCalls = 0
    fixture.transcript.selectCalls = 0
    fixture.transcript.clearCalls = 0
    fixture.keys("8l")
    assertEquals("alpha bet", fixture.transcript.selectedText())
    assertEquals(1, fixture.transcript.selectCalls)
    assertEquals(1, fixture.transcript.clearCalls)
    assertEquals(1, fixture.transcript.revealCalls)
  }

  @Test
  fun `leaving visual mode clears once and ordinary navigation does not clear again`() = onEdt {
    val fixture = Fixture()
    fixture.keys("ggvll")
    fixture.transcript.clearCalls = 0
    fixture.keys("vjjkk")
    assertEquals(1, fixture.transcript.clearCalls)
    assertEquals(null, fixture.transcript.selectedText())
  }

  @Test
  fun `batched vertical motion preserves the desired column across short rows`() = onEdt {
    val fixture = Fixture("abcdefgh\nx\nabcdefgh", "next")
    fixture.keys("gg5l2j")
    assertEquals(ChatPosition(0, 16), fixture.controller.cursor)
    fixture.keys("2k")
    assertEquals(ChatPosition(0, 5), fixture.controller.cursor)
  }

  @Test
  fun `character navigation reads the immutable session text only once`() = onEdt {
    val document = CountingDocument().apply { insertString(0, "alpha beta gamma", null) }
    val component = JTextArea(document)
    val part = ChatTextPart(component, JPanel())
    document.fullReads = 0
    repeat(100) { assertEquals(1, part.horizontal(0, SwingConstants.EAST)) }
    assertEquals(1, document.fullReads)
  }

  @Test
  fun `counts and boundaries move over native text without editing`() = onEdt {
    val fixture = Fixture()
    fixture.keys("gg3l")
    assertEquals(ChatPosition(0, 3), fixture.controller.cursor)
    fixture.keys("0h")
    assertEquals(ChatPosition(0, 0), fixture.controller.cursor)
    fixture.keys("G")
    assertEquals(1, fixture.controller.cursor.part)
    fixture.keys("l")
    assertEquals(fixture.transcript.parts.last().lastOffset, fixture.controller.cursor.offset)
    assertEquals(listOf("alpha beta\ngamma", "delta epsilon\nzeta"), fixture.transcript.parts.map { it.text })
  }

  @Test
  fun `visual selection spans messages and y copies and exits`() = onEdt {
    val fixture = Fixture()
    fixture.keys("ggvG")
    assertEquals("alpha beta\ngamma\n\ndelta epsilon\nzeta", fixture.transcript.selectedText())
    fixture.keys("y")
    assertEquals("alpha beta\ngamma\n\ndelta epsilon\nzeta", fixture.copied)
    assertTrue(fixture.exited)
  }

  @Test
  fun `reverse visual selection retains reading order`() = onEdt {
    val fixture = Fixture()
    fixture.keys("vgg")
    assertEquals("alpha beta\ngamma\n\ndelta epsilon\nzeta", fixture.transcript.selectedText())
    fixture.keys("o")
    assertEquals(1, fixture.controller.cursor.part)
    assertEquals(ChatPosition(0, 0), fixture.controller.anchor)
  }

  @Test
  fun `escape clears selection before exiting`() = onEdt {
    val fixture = Fixture()
    fixture.keys("ggvll")
    fixture.controller.escape()
    assertFalse(fixture.exited)
    assertEquals(null, fixture.controller.anchor)
    assertEquals(null, fixture.transcript.selectedText())
    fixture.controller.escape()
    assertTrue(fixture.exited)
  }

  @Test
  fun `escape cancels pending counts and command prefixes`() = onEdt {
    val fixture = Fixture()
    fixture.keys("3")
    fixture.controller.escape()
    assertFalse(fixture.exited)
    fixture.keys("g")
    fixture.controller.escape()
    assertFalse(fixture.exited)
    fixture.keys("gg")
    assertEquals(ChatPosition(0, 0), fixture.controller.cursor)
  }

  @Test
  fun `vertical movement follows rendered wrapped rows`() = onEdt {
    val fixture = Fixture("alpha beta gamma delta epsilon zeta eta theta", "next")
    fixture.keys("gg")
    val before = fixture.transcript.parts.first().bounds(fixture.controller.cursor.offset)
    fixture.keys("j")
    val after = fixture.transcript.parts.first().bounds(fixture.controller.cursor.offset)
    assertTrue(after.y > before.y)
    fixture.keys("k")
    assertEquals(ChatPosition(0, 0), fixture.controller.cursor)
  }

  @Test
  fun `downward movement skips zero height HTML rules without leaving the message`() = onEdt {
    val fixture = Fixture("<p>first</p><hr><p>second</p>", "next", html = true)
    val part = fixture.transcript.parts.first()
    fixture.keys("gg")
    fixture.keys("j")
    assertEquals(0, fixture.controller.cursor.part)
    val bounds = part.bounds(fixture.controller.cursor.offset)
    assertTrue(bounds.height > 0)
    assertEquals(part.bounds(part.text.indexOf("second")).y, bounds.y)
  }

  @Test
  fun `upward movement skips zero height HTML rules without leaving the message`() = onEdt {
    val fixture = Fixture("<p>first</p><hr><p>second</p>", "next", html = true)
    val part = fixture.transcript.parts.first()
    fixture.keys("gg")
    assertTrue(fixture.controller.search("second", true))
    fixture.keys("k")
    assertEquals(0, fixture.controller.cursor.part)
    val bounds = part.bounds(fixture.controller.cursor.offset)
    assertTrue(bounds.height > 0)
    assertEquals(part.bounds(part.firstOffset).y, bounds.y)
  }

  @Test
  fun `vertical movement crosses a message boundary`() = onEdt {
    val fixture = Fixture("first", "second")
    fixture.keys("ggjk")
    assertEquals(ChatPosition(0, 0), fixture.controller.cursor)
  }

  @Test
  fun `upward movement crosses HTML messages without stopping at structural offsets`() = onEdt {
    val fixture = Fixture("first", "second", html = true)
    val firstPart = fixture.transcript.parts.first()
    val secondPart = fixture.transcript.parts.last()
    assertTrue(secondPart.firstOffset > 0)
    fixture.keys("ggjl")
    assertEquals(ChatPosition(1, secondPart.firstOffset + 1), fixture.controller.cursor)
    fixture.keys("k")
    assertEquals(ChatPosition(0, firstPart.firstOffset + 1), fixture.controller.cursor)
  }

  @Test
  fun `repeated upward movement crosses HTML messages from their last row`() = onEdt {
    val fixture = Fixture("first<br>last", "second<br>bottom", html = true)
    fixture.keys("Gk")
    assertEquals(1, fixture.controller.cursor.part)
    val upperRow = fixture.transcript.parts.last().bounds(fixture.controller.cursor.offset)
    assertEquals(fixture.transcript.parts.last().bounds(fixture.transcript.parts.last().firstOffset).y, upperRow.y)
    fixture.keys("k")
    assertEquals(0, fixture.controller.cursor.part)
    assertEquals(
      fixture.transcript.parts.first().bounds(fixture.transcript.parts.first().lastOffset).y,
      fixture.transcript.parts.first().bounds(fixture.controller.cursor.offset).y,
    )
  }

  @Test
  fun `HTML vertical movement preserves the column and stops at transcript boundaries`() = onEdt {
    val fixture = Fixture("first", "second", html = true)
    val firstPart = fixture.transcript.parts.first()
    val secondPart = fixture.transcript.parts.last()
    fixture.keys("ggl3k")
    assertEquals(ChatPosition(0, firstPart.firstOffset + 1), fixture.controller.cursor)
    fixture.keys("3j")
    assertEquals(ChatPosition(1, secondPart.firstOffset + 1), fixture.controller.cursor)
    fixture.keys("3k")
    assertEquals(ChatPosition(0, firstPart.firstOffset + 1), fixture.controller.cursor)
  }

  @Test
  fun `upward visual selection crosses HTML messages without horizontal workarounds`() = onEdt {
    val fixture = Fixture("first", "second", html = true)
    fixture.keys("ggjvk")
    assertEquals(ChatPosition(0, fixture.transcript.parts.first().firstOffset), fixture.controller.cursor)
    val selectedText = assertNotNull(fixture.transcript.selectedText())
    assertTrue(selectedText.startsWith("first"))
    assertTrue(selectedText.endsWith("s"))
    fixture.keys("y")
    assertEquals(selectedText, fixture.copied)
    assertTrue(fixture.exited)
  }

  @Test
  fun `line selection uses native rendered row boundaries`() = onEdt {
    val fixture = Fixture()
    fixture.keys("ggV")
    assertEquals("alpha beta\n", fixture.transcript.selectedText())
    fixture.keys("j")
    assertEquals("alpha beta\ngamma", fixture.transcript.selectedText())
  }

  @Test
  fun `native word motions and row boundaries work`() = onEdt {
    val fixture = Fixture()
    fixture.keys("ggw")
    assertEquals(6, fixture.controller.cursor.offset)
    fixture.keys("b")
    assertEquals(0, fixture.controller.cursor.offset)
    fixture.keys("$")
    assertTrue(fixture.controller.cursor.offset > 0)
    fixture.keys("0")
    assertEquals(0, fixture.controller.cursor.offset)
  }

  @Test
  fun `literal search crosses messages wraps and repeats`() = onEdt {
    val fixture = Fixture("alpha needle alpha", "other needle")
    fixture.keys("gg")
    assertTrue(fixture.controller.search("needle", true))
    assertEquals(ChatPosition(0, 6), fixture.controller.cursor)
    fixture.keys("n")
    assertEquals(ChatPosition(1, 6), fixture.controller.cursor)
    fixture.keys("n")
    assertEquals(ChatPosition(0, 6), fixture.controller.cursor)
    fixture.keys("N")
    assertEquals(ChatPosition(1, 6), fixture.controller.cursor)
    fixture.keys("gg")
    assertTrue(fixture.controller.search("other", false))
    assertEquals(ChatPosition(1, 0), fixture.controller.cursor)
    assertFalse(fixture.controller.search("absent", true))
  }

  @Test
  fun `a single unicode character is not split when copied`() = onEdt {
    val fixture = Fixture("😀x", "end")
    fixture.keys("ggvy")
    assertEquals("😀", fixture.copied)
  }

  @Test
  fun `HTML document structural positions are skipped without dropping visible text`() = onEdt {
    val root = JPanel(null).apply { setSize(400, 300) }
    val pane = JEditorPane("text/html", "<html><body>alpha beta</body></html>").apply { setBounds(0, 0, 300, 200) }
    root.add(pane)
    val part = ChatTextPart(pane, root)
    assertTrue(part.firstOffset > 0)
    assertNotNull(part.pointAt(part.firstOffset))
    assertNotNull(part.pointAt(part.characterEnd(part.lastOffset)))
    assertEquals("alpha beta", part.text.substring(part.firstOffset))
  }

  @Test
  fun `block cursor covers the rendered character instead of the thin caret`() = onEdt {
    val part = Fixture().transcript.parts.first()
    val cursor = part.cursorBounds(0)
    assertEquals(part.bounds(1).x - part.bounds(0).x, cursor.width)
    assertTrue(cursor.width > 2)
    assertEquals(part.bounds(0).height, cursor.height)
  }

  @Test
  fun `block cursor uses a space cell at a newline`() = onEdt {
    val part = Fixture().transcript.parts.first()
    val offset = part.text.indexOf('\n')
    val cursor = part.cursorBounds(offset)
    assertEquals(part.bounds(offset).y, cursor.y)
    assertEquals(part.component.getFontMetrics(part.component.font).charWidth(' '), cursor.width)
  }

  @Test
  fun `block cursor follows native HTML glyph widths`() = onEdt {
    val part = Fixture("Wi", html = true).transcript.parts.first()
    val offset = part.firstOffset
    assertEquals(part.bounds(offset + 1).x - part.bounds(offset).x, part.cursorBounds(offset).width)
    assertTrue(part.cursorBounds(part.lastOffset).width > 0)
  }

  @Test
  fun `block cursor covers a complete surrogate pair`() = onEdt {
    val part = Fixture("😀x").transcript.parts.first()
    assertEquals(part.bounds(2).x - part.bounds(0).x, part.cursorBounds(0).width)
    assertTrue(part.cursorBounds(0).width > 0)
  }

  @Test
  fun `y without a selection does not overwrite the clipboard or exit`() = onEdt {
    val fixture = Fixture()
    fixture.keys("y")
    assertEquals(null, fixture.copied)
    assertFalse(fixture.exited)
  }

  @Test
  fun `paging moves the cursor towards the next message`() = onEdt {
    val fixture = Fixture()
    fixture.keys("gg")
    fixture.controller.page(1.0)
    assertEquals(1, fixture.controller.cursor.part)
    fixture.controller.page(-1.0)
    assertEquals(0, fixture.controller.cursor.part)
  }

  @Test
  fun `unreviewed Air versions are rejected before any UI changes`() = onEdt {
    val page = JPanel()
    assertFailsWith<ChatCopyUnavailable> { AirTranscriptSurface.connect(page, "new-version") }
    assertEquals(0, page.componentCount)
    assertFailsWith<ChatCopyUnavailable> { AirTranscriptSurface.connect(page, AirTranscriptSurface.SUPPORTED_VERSION) }
  }

  private class Fixture(vararg text: String = arrayOf("alpha beta\ngamma", "delta epsilon\nzeta"), html: Boolean = false) {
    val transcript = TestTranscript(text.toList(), html)
    var copied: String? = null
    var exited = false
    val controller = ChatCopyController(transcript, { copied = it }, { exited = true })
    fun keys(keys: String) = keys.forEach(controller::character)
  }

  private class TestTranscript(text: List<String>, html: Boolean) : ChatTranscript {
    var clearCalls = 0
    var selectCalls = 0
    var revealCalls = 0
    private val root = JPanel(null).apply { setSize(300, 600) }
    override val parts = text.mapIndexed { index, content ->
      val component = if (html) JEditorPane("text/html", "<html><body>$content</body></html>")
      else JTextArea(content).apply { lineWrap = true }
      component.apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        setBounds(0, index * 200, 150, 180)
      }
      root.add(component)
      ChatTextPart(component, root)
    }
    override val pageHeight = 200

    override fun select(start: ChatPosition, exclusiveEnd: ChatPosition) {
      selectCalls++
      for (index in start.part..exclusiveEnd.part) {
        val component = parts[index].component
        component.select(if (index == start.part) start.offset else 0,
                         if (index == exclusiveEnd.part) exclusiveEnd.offset else component.document.length)
      }
    }

    override fun clearSelection() {
      clearCalls++
      parts.forEach { it.component.select(0, 0) }
    }
    override fun selectedText(): String? = parts.mapNotNull { it.component.selectedText }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    override fun reveal(position: ChatPosition) {
      revealCalls++
      assertNotNull(parts[position.part].bounds(position.offset))
    }
  }

  private class CountingDocument : PlainDocument() {
    var fullReads = 0

    override fun getText(offset: Int, length: Int): String {
      if (offset == 0 && length == this.length) fullReads++
      return super.getText(offset, length)
    }
  }

  private fun onEdt(test: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) test() else SwingUtilities.invokeAndWait(test)
  }
}
