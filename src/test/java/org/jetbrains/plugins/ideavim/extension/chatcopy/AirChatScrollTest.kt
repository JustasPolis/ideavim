package org.jetbrains.plugins.ideavim.extension.chatcopy

import com.maddyhome.idea.vim.extension.chatcopy.AirChatScroll
import com.maddyhome.idea.vim.extension.chatcopy.ChatCopyController
import com.maddyhome.idea.vim.extension.chatcopy.ChatPosition
import com.maddyhome.idea.vim.extension.chatcopy.ChatTextPart
import com.maddyhome.idea.vim.extension.chatcopy.ChatTranscript
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AirChatScrollTest {
  @Test
  fun `native reveal scrolls upwards even when generic child reveals are filtered`() = onEdt {
    val fixture = ScrollFixture()
    fixture.pane.viewport.viewPosition = Point(0, 400)
    val bounds = fixture.part.cursorBounds(0)
    fixture.host.scrollRectToVisible(Rectangle(bounds))
    assertEquals(400, fixture.pane.viewport.viewPosition.y)
    fixture.scrolling.reveal(fixture.host, bounds)
    assertEquals(0, fixture.pane.viewport.viewPosition.y)
    assertTrue(fixture.pane.viewport.viewRect.contains(bounds))
    assertFalse(fixture.pane.following)
  }

  @Test
  fun `native reveal scrolls downwards to keep the complete block visible`() = onEdt {
    val fixture = ScrollFixture()
    val bounds = fixture.part.cursorBounds(fixture.part.lastOffset)
    fixture.scrolling.reveal(fixture.host, bounds)
    assertTrue(fixture.pane.viewport.viewPosition.y > 0)
    assertTrue(fixture.pane.viewport.viewRect.contains(bounds))
    assertEquals(fixture.pane.viewport.extentSize.height, fixture.scrolling.pageHeight)
  }

  @Test
  fun `revealing a visible row leaves the viewport in place`() = onEdt {
    val fixture = ScrollFixture()
    fixture.pane.viewport.viewPosition = Point(0, 200)
    fixture.scrolling.reveal(fixture.host, Rectangle(10, 220, 9, 18))
    assertEquals(200, fixture.pane.viewport.viewPosition.y)
  }

  @Test
  fun `repeated k and j scroll when the cursor passes the viewport edges`() = onEdt {
    val fixture = ScrollFixture()
    val transcript = object : ChatTranscript {
      override val parts = listOf(fixture.part)
      override val pageHeight get() = fixture.scrolling.pageHeight
      override fun select(start: ChatPosition, exclusiveEnd: ChatPosition) = Unit
      override fun clearSelection() = Unit
      override fun selectedText(): String? = null
      override fun reveal(position: ChatPosition) {
        fixture.scrolling.reveal(fixture.host, fixture.part.cursorBounds(position.offset))
      }
    }
    val controller = ChatCopyController(transcript, {}, {})
    controller.character('G')
    val bottom = fixture.pane.viewport.viewPosition.y
    assertTrue(bottom > 0)
    repeat(50) {
      controller.character('k')
      assertTrue(fixture.pane.viewport.viewRect.contains(fixture.part.cursorBounds(controller.cursor.offset)))
    }
    assertEquals(0, fixture.pane.viewport.viewPosition.y)
    repeat(50) {
      controller.character('j')
      assertTrue(fixture.pane.viewport.viewRect.contains(fixture.part.cursorBounds(controller.cursor.offset)))
    }
    assertEquals(bottom, fixture.pane.viewport.viewPosition.y)
  }

  @Test
  fun `restoring a bottom-following transcript re-enables native following`() = onEdt {
    val fixture = ScrollFixture()
    fixture.scrolling.reveal(fixture.host, Rectangle(0, 200, 9, 18))
    assertFalse(fixture.pane.following)
    fixture.pane.viewport.viewPosition = Point(0, fixture.pane.maximum)
    fixture.scrolling.restoreFollowing()
    assertTrue(fixture.pane.following)
    assertEquals(1, fixture.pane.resumeCalls)
  }

  @Test
  fun `restoring a transcript that was not following does not enable following`() = onEdt {
    val fixture = ScrollFixture(following = false)
    fixture.pane.following = true
    fixture.scrolling.restoreFollowing()
    assertFalse(fixture.pane.following)
    assertEquals(0, fixture.pane.resumeCalls)
  }

  private class ScrollFixture(following: Boolean = true) {
    val host = JPanel(null).apply { preferredSize = Dimension(240, 900) }
    val text = JTextArea((1..40).joinToString("\n") { "message row $it" }).apply {
      font = Font(Font.MONOSPACED, Font.PLAIN, 14)
      setBounds(0, 0, 220, 850)
    }
    val pane = NativeScrollPane(host).apply {
      this.following = following
      setSize(260, 160)
      doLayout()
      viewport.viewSize = host.preferredSize
    }
    val scrolling = AirChatScroll(pane)
    val part = ChatTextPart(text, host)

    init {
      host.add(text)
    }
  }

  class NativeScrollPane(content: JComponent) : JScrollPane(content) {
    var following = true
    var resumeCalls = 0
    val maximum: Int get() = (viewport.viewSize.height - viewport.extentSize.height).coerceAtLeast(0)

    override fun createViewport(): JViewport = object : JViewport() {
      override fun scrollRectToVisible(contentRect: Rectangle) = Unit
    }

    fun reveal(source: Component, bounds: Rectangle) {
      val target = SwingUtilities.convertRectangle(source, bounds, viewport.view)
      val visible = viewport.viewRect
      val destination = when {
        target.y < visible.y -> target.y
        target.y + target.height > visible.y + visible.height -> target.y + target.height - visible.height
        else -> return
      }.coerceIn(0, maximum)
      following = destination == maximum
      viewport.viewPosition = Point(visible.x, destination)
    }

    fun isFollowingBottom(): Boolean = following

    fun resumeFollowingBottomIfReached(reason: String) {
      require(reason.isNotBlank())
      resumeCalls++
      if (viewport.viewPosition.y == maximum) following = true
    }

    fun stopFollowingBottomForUserAction(reason: String) {
      require(reason.isNotBlank())
      following = false
    }
  }

  private fun onEdt(test: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) test() else SwingUtilities.invokeAndWait(test)
  }
}
