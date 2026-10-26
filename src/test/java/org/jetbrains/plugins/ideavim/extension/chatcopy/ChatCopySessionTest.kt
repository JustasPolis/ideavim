package org.jetbrains.plugins.ideavim.extension.chatcopy

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.UIUtil
import com.maddyhome.idea.vim.extension.chatcopy.AirTranscriptSurface
import com.maddyhome.idea.vim.extension.chatcopy.ChatCopyFocus
import com.maddyhome.idea.vim.extension.chatcopy.ChatCopySession
import com.maddyhome.idea.vim.extension.chatcopy.ChatPosition
import com.maddyhome.idea.vim.extension.chatcopy.ChatTextPart
import org.jetbrains.plugins.ideavim.VimTestCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.awt.Component
import java.awt.BorderLayout
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JLayeredPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.RepaintManager
import javax.swing.text.AbstractDocument
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCopySessionTest : VimTestCase() {
  @Test
  fun `deferred focus activation requests an initial paint even when the cursor has not moved`() = onEdt {
    val state = SessionFixture()
    state.focus.defer = true
    state.session.start()
    val overlay = state.root.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER).single() as JComponent
    val previousManager = RepaintManager.currentManager(overlay)
    val manager = RecordingRepaintManager(overlay)
    RepaintManager.setCurrentManager(manager)
    try {
      state.focus.acceptPending()
      assertTrue(manager.regions.any { it == Rectangle(0, 0, overlay.width, overlay.height) })
      assertTrue(state.session.isActive)
    } finally {
      RepaintManager.setCurrentManager(previousManager)
      state.session.finish(false)
    }
    state.assertClean()
  }

  @Test
  fun `cursor movement repaints only the old and new blocks`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    "gg".forEach { state.session.dispatch(typed(state.host, it)) }
    val overlay = state.root.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER).single() as JComponent
    val previousManager = RepaintManager.currentManager(overlay)
    val manager = RecordingRepaintManager(overlay)
    RepaintManager.setCurrentManager(manager)
    try {
      state.session.dispatch(typed(state.host, 'l'))
      assertEquals(2, manager.regions.size)
      assertTrue(manager.regions.all { it.width < overlay.width && it.height < overlay.height })
    } finally {
      RepaintManager.setCurrentManager(previousManager)
      state.session.finish(true)
    }
    state.assertClean()
  }

  @Test
  fun `changing mode repaints the footer without repainting the full transcript`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    val overlay = state.root.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER).single() as JComponent
    val previousManager = RepaintManager.currentManager(overlay)
    val manager = RecordingRepaintManager(overlay)
    RepaintManager.setCurrentManager(manager)
    try {
      state.session.dispatch(typed(state.host, 'v'))
      assertEquals(listOf(Rectangle(0, overlay.height - 26, overlay.width, 26)), manager.regions)
    } finally {
      RepaintManager.setCurrentManager(previousManager)
      state.session.finish(true)
    }
    state.assertClean()
  }

  @Test
  fun `key releases do not recollect the transcript or repaint the overlay`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    val overlay = state.root.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER).single() as JComponent
    val previousManager = RepaintManager.currentManager(overlay)
    val manager = RecordingRepaintManager(overlay)
    RepaintManager.setCurrentManager(manager)
    val checks = state.structureChecks
    try {
      assertTrue(state.session.dispatch(pressed(state.root, KeyEvent.VK_J, KeyEvent.KEY_RELEASED)))
      assertEquals(checks, state.structureChecks)
      assertTrue(manager.regions.isEmpty())
      assertTrue(state.session.isActive)
    } finally {
      RepaintManager.setCurrentManager(previousManager)
      state.session.finish(true)
    }
    state.assertClean()
  }

  @Test
  fun `overlay paints a filled character block instead of a two pixel bar`() = onEdt {
    val state = SessionFixture()
    state.root.doLayout()
    state.root.contentPane.doLayout()
    state.session.start()
    "gg".forEach { state.session.dispatch(typed(state.host, it)) }
    val overlay = state.root.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER).single() as JComponent
    val bounds = SwingUtilities.convertRectangle(
      state.host, state.surface.parts.first().cursorBounds(state.session.controller.cursor.offset), overlay,
    )
    assertTrue(bounds.width > 2)
    val image = BufferedImage(overlay.width, overlay.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
      overlay.paint(graphics)
    } finally {
      graphics.dispose()
    }
    assertTrue(image.getRGB(bounds.x + bounds.width - 1, bounds.y + bounds.height / 2) ushr 24 > 0)
    assertEquals(0, image.getRGB(bounds.x + bounds.width, bounds.y + bounds.height / 2) ushr 24)
    state.session.finish(true)
    state.assertClean()
  }

  @Test
  fun `copy cursor uses the overlay layer rather than the component index overload`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    assertEquals(1, state.root.layeredPane.getComponentsInLayer(JLayeredPane.DRAG_LAYER).size)
    state.session.finish(true)
    state.assertClean()
  }

  @Test
  fun `production focus gateway refuses components without an active window`() = onEdt {
    val state = SessionFixture()
    val session = ChatCopySession(state.surface, {}, {}, vimEnabled = { true })
    session.start()
    assertFalse(session.isActive)
    state.assertClean()
  }

  @Test
  fun `rejected focus requests remove all installed resources`() = onEdt {
    val state = SessionFixture()
    state.focus.reject = true
    state.session.start()
    assertFalse(state.session.isActive)
    state.assertClean()
    assertEquals(1, state.focus.requests.size)
  }

  @Test
  fun `keys pass through until focus activation is accepted`() = onEdt {
    val state = SessionFixture()
    state.focus.defer = true
    state.session.start()
    assertFalse(state.session.dispatch(typed(state.composer, 'j')))
    assertTrue(state.session.isActive)
    state.focus.acceptPending()
    assertTrue(state.session.dispatch(typed(state.host, 'j')))
    state.session.finish(true)
    state.assertClean()
  }

  @Test
  fun `activation shortcut release from the composer does not cancel mode`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    assertTrue(state.session.dispatch(pressed(state.composer, KeyEvent.VK_X, KeyEvent.KEY_RELEASED)))
    assertTrue(state.session.isActive)
    state.session.finish(true)
  }

  @Test
  fun `raw top-level shortcut and modifier releases keep copy mode latched`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    assertTrue(state.session.dispatch(pressed(
      state.root, KeyEvent.VK_X, KeyEvent.KEY_RELEASED, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK,
    )))
    assertTrue(state.session.dispatch(pressed(state.root, KeyEvent.VK_SHIFT, KeyEvent.KEY_RELEASED, KeyEvent.CTRL_DOWN_MASK)))
    assertTrue(state.session.dispatch(pressed(state.root, KeyEvent.VK_CONTROL, KeyEvent.KEY_RELEASED)))
    assertTrue(state.session.isActive)
    assertEquals(state.host, state.focus.owner)
    assertEquals("unsent composer text", state.composer.text)
    state.session.finish(true)
    state.assertClean()
  }

  @Test
  fun `raw top-level navigation keys are routed to the focused transcript`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    "ggll".forEach { assertTrue(state.session.dispatch(typed(state.root, it))) }
    assertEquals(ChatPosition(0, 2), state.session.controller.cursor)
    assertTrue(state.session.isActive)
    assertEquals("alpha beta", state.text.text)
    state.session.finish(true)
    state.assertClean()
  }

  @Test
  fun `events from another root are never stolen by copy mode`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    val otherRoot = JRootPane()
    assertFalse(state.session.dispatch(typed(otherRoot, 'j')))
    assertFalse(state.session.isActive)
    assertEquals(listOf<Component>(state.host), state.focus.requests)
    state.assertClean()
  }

  @Test
  fun `Enter including its typed and release events stays out of the composer`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    assertTrue(state.session.dispatch(pressed(state.host, KeyEvent.VK_ENTER)))
    assertTrue(state.session.dispatch(typed(state.host, '\n')))
    assertTrue(state.session.dispatch(pressed(state.host, KeyEvent.VK_ENTER, KeyEvent.KEY_RELEASED)))
    assertEquals("unsent composer text", state.composer.text)
    assertTrue(state.session.isActive)
    state.session.finish(true)
  }

  @Test
  fun `Escape is drained through release before focus returns to the composer`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    assertTrue(state.session.dispatch(pressed(state.host, KeyEvent.VK_ESCAPE)))
    assertTrue(state.session.isActive)
    assertEquals(state.host, state.focus.owner)
    assertTrue(state.session.dispatch(typed(state.host, 27.toChar())))
    assertTrue(state.session.dispatch(pressed(state.host, KeyEvent.VK_ESCAPE, KeyEvent.KEY_RELEASED)))
    assertFalse(state.session.isActive)
    assertEquals(state.composer, state.focus.owner)
    assertEquals("unsent composer text", state.composer.text)
    state.assertClean()
  }

  @Test
  fun `control C cannot leak a typed event into the composer`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    assertTrue(state.session.dispatch(pressed(state.host, KeyEvent.VK_C, modifiers = KeyEvent.CTRL_DOWN_MASK)))
    assertTrue(state.session.dispatch(typed(state.host, 3.toChar())))
    assertEquals(state.host, state.focus.owner)
    assertTrue(state.session.dispatch(pressed(state.host, KeyEvent.VK_C, KeyEvent.KEY_RELEASED, KeyEvent.CTRL_DOWN_MASK)))
    assertFalse(state.session.isActive)
    assertEquals("unsent composer text", state.composer.text)
    state.assertClean()
  }

  @Test
  fun `closing search drains trailing Enter events without exiting copy mode`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    val field = JTextField()
    state.session.searchClosed(field)
    assertTrue(state.session.dispatch(typed(field, '\n')))
    assertTrue(state.session.dispatch(pressed(field, KeyEvent.VK_ENTER, KeyEvent.KEY_RELEASED)))
    assertTrue(state.session.isActive)
    assertEquals(state.host, state.focus.owner)
    assertEquals("unsent composer text", state.composer.text)
    state.session.finish(true)
    state.assertClean()
  }

  @Test
  fun `typing into another component exits without reclaiming focus`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    val outside = JTextArea()
    state.focus.owner = outside
    assertFalse(state.session.dispatch(typed(outside, 'j')))
    assertEquals(outside, state.focus.owner)
    assertEquals<List<Component>>(listOf(state.host), state.focus.requests)
    assertEquals(false, state.restoredScroll)
    state.assertClean()
  }

  @Test
  fun `backgrounding the window cannot cause a focus request on cleanup`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    state.focus.owner = null
    state.session.finish(true)
    assertEquals<List<Component>>(listOf(state.host), state.focus.requests)
    state.assertClean()
  }

  @Test
  fun `changed transcript structure exits without restoring a stale viewport`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    state.current = false
    assertTrue(state.session.dispatch(typed(state.host, 'j')))
    assertFalse(state.session.isActive)
    assertEquals(false, state.restoredScroll)
    state.assertClean()
  }

  @Test
  fun `streaming text cancels copy mode and removes listeners`() {
    val state = onEdt {
      SessionFixture().also {
        it.session.start()
        it.text.document.insertString(it.text.document.length, " streamed", null)
      }
    }
    onEdt {
      UIUtil.dispatchAllInvocationEvents()
      assertFalse(state.session.isActive)
      assertEquals(false, state.restoredScroll)
      assertEquals("unsent composer text", state.composer.text)
      state.assertClean()
    }
  }

  @Test
  fun `visual yank uses the supplied clipboard sink and exits`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    "ggvlly".forEach { state.session.dispatch(typed(state.host, it)) }
    assertEquals("alp", state.copied)
    assertFalse(state.session.isActive)
    assertEquals("alpha beta", state.text.text)
    state.assertClean()
  }

  @Test
  fun `cleanup is idempotent and disposed modes do not intercept keys`() = onEdt {
    val state = SessionFixture()
    state.session.start()
    state.session.finish(true)
    state.session.finish(true)
    assertEquals(1, state.closed)
    assertFalse(state.session.dispatch(typed(state.host, 'j')))
    state.assertClean()
  }

  private inner class SessionFixture {
    val composer = object : JTextArea("unsent composer text") {
      override fun isShowing() = true
    }
    val root = JRootPane().apply { setSize(400, 300) }
    val page = JPanel(null).apply { setBounds(0, 0, 400, 300) }
    val host = JPanel(null).apply { setBounds(0, 0, 400, 300); isFocusable = false; isRequestFocusEnabled = false }
    val text = JTextArea("alpha beta").apply { setBounds(0, 0, 300, 100) }
    val focus = TestFocus(composer)
    var copied: String? = null
    var restoredScroll: Boolean? = null
    var closed = 0
    var current = true
    var structureChecks = 0
    val surface = mock<AirTranscriptSurface>()
    private val focusListenersBefore = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPropertyChangeListeners("focusOwner").size
    private val mouseListenersBefore = Toolkit.getDefaultToolkit().awtEventListeners.size
    private val documentListenersBefore = (text.document as AbstractDocument).documentListeners.size
    private val overlaysBefore: Int
    val session: ChatCopySession

    init {
      root.contentPane.add(page)
      root.contentPane.add(composer, BorderLayout.SOUTH)
      page.add(host)
      host.add(text)
      overlaysBefore = root.layeredPane.componentCount
      whenever(surface.page).thenReturn(page)
      whenever(surface.host).thenReturn(host)
      whenever(surface.parts).thenReturn(listOf(ChatTextPart(text, host)))
      whenever(surface.pageHeight).thenReturn(200)
      whenever(surface.isCurrent()).thenAnswer { structureChecks++; current }
      whenever(surface.selectedText()).thenAnswer { text.selectedText }
      doAnswer { text.select(0, 0); null }.whenever(surface).clearSelection()
      doAnswer {
        text.select(it.getArgument<ChatPosition>(0).offset, it.getArgument<ChatPosition>(1).offset)
        null
      }.whenever(surface).select(any(), any())
      doAnswer { restoredScroll = it.getArgument(0); null }.whenever(surface).close(any())
      session = ChatCopySession(surface, {}, { closed++ }, focus, { copied = it }, { true })
      Disposer.register(fixture.testRootDisposable) { session.finish(false) }
    }

    fun assertClean() {
      assertEquals(focusListenersBefore, KeyboardFocusManager.getCurrentKeyboardFocusManager().getPropertyChangeListeners("focusOwner").size)
      assertEquals(mouseListenersBefore, Toolkit.getDefaultToolkit().awtEventListeners.size)
      assertEquals(documentListenersBefore, (text.document as AbstractDocument).documentListeners.size)
      assertEquals(overlaysBefore, root.layeredPane.componentCount)
      assertFalse(host.isFocusable)
      assertFalse(host.isRequestFocusEnabled)
    }
  }

  private class TestFocus(override var owner: Component?) : ChatCopyFocus {
    val requests = mutableListOf<Component>()
    var reject = false
    var defer = false
    private var pending: (() -> Unit)? = null

    override fun request(component: Component, accepted: () -> Unit, rejected: () -> Unit) {
      requests += component
      when {
        reject -> rejected()
        defer -> pending = { owner = component; accepted() }
        else -> { owner = component; accepted() }
      }
    }

    fun acceptPending() {
      pending?.invoke()
      pending = null
    }
  }

  private class RecordingRepaintManager(private val target: JComponent) : RepaintManager() {
    val regions = mutableListOf<Rectangle>()

    override fun addDirtyRegion(component: JComponent, x: Int, y: Int, width: Int, height: Int) {
      if (component === target) regions += Rectangle(x, y, width, height)
      super.addDirtyRegion(component, x, y, width, height)
    }
  }

  private fun typed(component: Component, character: Char) =
    KeyEvent(component, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, character)

  private fun pressed(component: Component, code: Int, id: Int = KeyEvent.KEY_PRESSED, modifiers: Int = 0) =
    KeyEvent(component, id, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED)

  private fun <Result> onEdt(test: () -> Result): Result {
    var result: Result? = null
    ApplicationManager.getApplication().invokeAndWait { result = test() }
    @Suppress("UNCHECKED_CAST")
    return result as Result
  }
}
