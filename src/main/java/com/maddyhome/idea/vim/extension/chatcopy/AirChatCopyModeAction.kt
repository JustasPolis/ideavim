package com.maddyhome.idea.vim.extension.chatcopy

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.maddyhome.idea.vim.VimPlugin
import java.awt.AWTEvent
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AirChatCopyModeAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabled = VimPlugin.isEnabled() && event.project != null &&
      AirTranscriptSurface.findPage(contextComponent(event)) != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val page = AirTranscriptSurface.findPage(contextComponent(event)) ?: return
    project.service<AirChatCopyModeService>().toggle(page)
  }

  private fun contextComponent(event: AnActionEvent): Component? =
    event.getData(CommonDataKeys.EDITOR)?.contentComponent ?: event.getData(PlatformDataKeys.CONTEXT_COMPONENT)
}

@Service(Service.Level.PROJECT)
internal class AirChatCopyModeService(private val project: Project) : Disposable {
  private var session: ChatCopySession? = null
  private var preparation: ChatTextPreparation? = null

  fun toggle(page: JComponent) {
    session?.let { it.finish(true); return }
    preparation?.let { it.cancel(); return }
    val version = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.air"))?.version
    val work = ChatTextPreparation(
      pending = { AirTranscriptSurface.pendingPanels(page, version) },
      materialize = AirTranscriptSurface::materialize,
      canContinue = {
        !project.isDisposed && page.isShowing && VimPlugin.isEnabled() &&
          SwingUtilities.getWindowAncestor(page)?.isActive == true &&
          AirTranscriptSurface.findPage(KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner) == page
      },
      ready = {
        preparation = null
        activate(page, version)
      },
      failed = {
        preparation = null
        status(it.message ?: "Chat copy mode could not prepare the transcript")
      },
      cancelled = { preparation = null },
    )
    preparation = work
    work.start()
  }

  private fun activate(page: JComponent, version: String?) {
    try {
      val surface = AirTranscriptSurface.connect(page, version)
      val created = ChatCopySession(surface, { message -> status(message) }, { session = null })
      session = created
      created.start()
    } catch (failure: IllegalStateException) {
      session?.finish(false)
      status(failure.message ?: "Chat copy mode could not start")
    }
  }

  private fun status(message: String) {
    logger<AirChatCopyModeService>().warn("Air chat copy mode: $message")
    if (project.isDisposed) return
    WindowManager.getInstance().getStatusBar(project)?.info = message
    NotificationGroupManager.getInstance().getNotificationGroup("ideavim-sticky")
      .createNotification("Air chat copy mode", message, NotificationType.WARNING).notify(project)
  }

  override fun dispose() {
    preparation?.cancel()
    session?.finish(false)
  }
}

internal interface ChatCopyFocus {
  val owner: Component?
  fun request(component: Component, accepted: () -> Unit, rejected: () -> Unit)
}

private object IdeChatCopyFocus : ChatCopyFocus {
  override val owner: Component? get() = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

  override fun request(component: Component, accepted: () -> Unit, rejected: () -> Unit) {
    val window = SwingUtilities.getWindowAncestor(component)
    if (window?.isActive != true) {
      rejected()
      return
    }
    IdeFocusManager.getGlobalInstance().requestFocus(component, false)
      .doWhenDone(accepted).doWhenRejected(rejected)
  }
}

internal class ChatCopySession(
  private val surface: AirTranscriptSurface,
  private val status: (String) -> Unit,
  private val onClose: () -> Unit,
  private val focus: ChatCopyFocus = IdeChatCopyFocus,
  private val copy: (String) -> Unit = { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
  private val vimEnabled: () -> Boolean = VimPlugin::isEnabled,
) {
  private val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
  private val originalFocus = focus.owner
  private val originalFocusable = surface.host.isFocusable
  private val originalRequestFocusEnabled = surface.host.isRequestFocusEnabled
  private val layeredPane = requireNotNull(SwingUtilities.getRootPane(surface.page)).layeredPane
  private val overlay = object : JComponent() {
    override fun contains(x: Int, y: Int) = false
    override fun paintComponent(graphics: Graphics) {
      if (finished || invalidated || !armed) return
      val caret = overlayCursor ?: return
      val cursorGraphics = graphics.create() as Graphics2D
      try {
        cursorGraphics.clip(SwingUtilities.convertRectangle(surface.host, surface.host.visibleRect, this))
        cursorGraphics.color = UIManager.getColor("Editor.caretColor") ?: UIManager.getColor("Label.foreground")
        cursorGraphics.composite = AlphaComposite.SrcOver.derive(0.6f)
        cursorGraphics.fillRect(caret.x, caret.y, caret.width, caret.height)
      } finally {
        cursorGraphics.dispose()
      }
      val caption = "$overlayStatus   v/V select · y copy · / search · q exit"
      graphics.color = UIManager.getColor("Panel.background")
      graphics.fillRect(0, height - 26, width, 26)
      graphics.color = UIManager.getColor("Label.foreground")
      graphics.font = UIManager.getFont("Label.font")
      graphics.drawString(caption, 8, height - 8)
    }
  }
  val controller = ChatCopyController(
    surface, copy, { requestExit() },
  )
  val isActive: Boolean get() = !finished
  private var finished = false
  private var armed = false
  private var invalidated = false
  private var dispatchingPressedKey: Int? = null
  private var exitOnRelease: Int? = null
  private var searching: JTextField? = null
  private var retiredSearchField: JTextField? = null
  private var searchPopup: JBPopup? = null
  private var overlayCursor: Rectangle? = null
  private var overlayStatus: String? = null
  private val dispatcherLifetime = Disposer.newDisposable("Air chat copy keys")
  private val keyDispatcher = IdeEventQueue.EventDispatcher { event -> event is KeyEvent && dispatch(event) }
  private val focusListener = PropertyChangeListener { event ->
    val component = event.newValue as? Component
    if (armed && component != null && component != surface.host && !inSearch(component)) finish(false)
  }
  private val mouseListener = AWTEventListener { event ->
    if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED && !inSearch(event.component)) finish(false)
  }
  private val hierarchyListener = HierarchyListener {
    if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !surface.page.isShowing) finish(false)
  }
  private val documentListener = object : DocumentListener {
    override fun insertUpdate(event: DocumentEvent) = invalidate()
    override fun removeUpdate(event: DocumentEvent) = invalidate()
    override fun changedUpdate(event: DocumentEvent) = invalidate()
  }
  private val scrollListeners = mutableListOf<Pair<javax.swing.JViewport, javax.swing.event.ChangeListener>>()

  fun start() {
    overlay.isOpaque = false
    layeredPane.add(overlay, JLayeredPane.DRAG_LAYER, -1)
    surface.parts.forEach { it.component.document.addDocumentListener(documentListener) }
    generateSequence(surface.host as Component) { it.parent }.filterIsInstance<javax.swing.JViewport>().forEach {
      val listener = javax.swing.event.ChangeListener { render() }
      it.addChangeListener(listener)
      scrollListeners += it to listener
    }
    surface.page.addHierarchyListener(hierarchyListener)
    surface.host.isFocusable = true
    surface.host.isRequestFocusEnabled = true
    IdeEventQueue.getInstance().addDispatcher(keyDispatcher, dispatcherLifetime)
    focusManager.addPropertyChangeListener("focusOwner", focusListener)
    Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener, AWTEvent.MOUSE_EVENT_MASK)
    render()
    focus.request(surface.host, {
      if (!finished) {
        armed = true
        try {
          surface.reveal(controller.cursor)
          render()
          overlay.repaint()
        } catch (failure: Exception) {
          finish(true)
          status("Chat copy mode could not display the current text layout")
        }
      }
    }, {
      finish(false)
      status("Could not focus Air's transcript; copy mode was not enabled")
    })
  }

  internal fun dispatch(event: KeyEvent): Boolean {
    if (finished) return false
    if (event.component == retiredSearchField && event.id != KeyEvent.KEY_PRESSED) {
      if (event.id == KeyEvent.KEY_RELEASED) retiredSearchField = null
      return true
    }
    if (!armed || inSearch(event.component)) return false
    val eventRoot = SwingUtilities.getRoot(event.component)
      ?: SwingUtilities.getRootPane(event.component) ?: event.component
    val hostRoot = SwingUtilities.getRoot(surface.host)
      ?: SwingUtilities.getRootPane(surface.host) ?: surface.host
    if (focus.owner != surface.host || eventRoot !== hostRoot) {
      finish(false)
      return false
    }
    if (!vimEnabled() || invalidated) {
      finish(true)
      return true
    }
    exitOnRelease?.let { key ->
      if (event.id == KeyEvent.KEY_RELEASED && event.keyCode == key) finish(true)
      return true
    }
    if (event.id == KeyEvent.KEY_RELEASED) return true
    dispatchingPressedKey = event.keyCode.takeIf { event.id == KeyEvent.KEY_PRESSED }
    try {
      if (!surface.isCurrent()) {
        invalidated = true
        finish(true)
        return true
      }
      if (event.isMetaDown || event.isAltDown) {
        if (event.isMetaDown && event.keyCode == KeyEvent.VK_C && event.id == KeyEvent.KEY_PRESSED) {
          controller.character('y')
          return true
        }
        if (event.id == KeyEvent.KEY_PRESSED) finish(false)
        return false
      }
      when (event.id) {
        KeyEvent.KEY_TYPED -> {
          when (event.keyChar) {
            '/' -> search(true)
            '?' -> search(false)
            else -> if (!event.isControlDown) controller.character(event.keyChar)
          }
        }
        KeyEvent.KEY_PRESSED -> when {
          event.keyCode == KeyEvent.VK_ESCAPE -> controller.escape()
          event.isControlDown && event.keyCode in listOf(KeyEvent.VK_C, KeyEvent.VK_OPEN_BRACKET) -> requestExit()
          event.isControlDown && event.keyCode == KeyEvent.VK_U -> controller.page(-0.5)
          event.isControlDown && event.keyCode == KeyEvent.VK_D -> controller.page(0.5)
          event.keyCode == KeyEvent.VK_PAGE_UP || event.isControlDown && event.keyCode == KeyEvent.VK_B -> controller.page(-1.0)
          event.keyCode == KeyEvent.VK_PAGE_DOWN || event.isControlDown && event.keyCode == KeyEvent.VK_F -> controller.page(1.0)
          event.keyCode == KeyEvent.VK_UP -> controller.character('k')
          event.keyCode == KeyEvent.VK_DOWN -> controller.character('j')
          event.keyCode == KeyEvent.VK_LEFT -> controller.character('h')
          event.keyCode == KeyEvent.VK_RIGHT -> controller.character('l')
          event.keyCode == KeyEvent.VK_HOME -> controller.character('0')
          event.keyCode == KeyEvent.VK_END -> controller.character('$')
        }
      }
      render()
    } catch (failure: Exception) {
      finish(true)
      status("Chat copy mode stopped: ${failure.message ?: "text layout changed"}")
    } finally {
      dispatchingPressedKey = null
    }
    return true
  }

  private fun requestExit() {
    val pressed = dispatchingPressedKey
    if (pressed != null) exitOnRelease = pressed else finish(true)
  }

  private fun invalidate() {
    if (invalidated || finished) return
    invalidated = true
    SwingUtilities.invokeLater {
      if (!finished) {
        finish(true)
        status("Chat changed; copy mode exited without modifying the conversation")
      }
    }
  }

  private fun search(forward: Boolean) {
    if (searchPopup != null) return
    val field = JTextField(24)
    searching = field
    val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(field, field)
      .setTitle(if (forward) "Find in chat (literal)" else "Find backwards in chat (literal)")
      .setRequestFocus(true).createPopup()
    searchPopup = popup
    field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chat-find")
    field.actionMap.put("chat-find", object : AbstractAction() {
      override fun actionPerformed(event: java.awt.event.ActionEvent) {
        if (!controller.search(field.text, forward)) status("No match in rendered chat text")
        popup.cancel()
        render()
      }
    })
    popup.addListener(object : JBPopupListener {
      override fun onClosed(event: LightweightWindowEvent) {
        searchClosed(field)
      }
    })
    popup.showInCenterOf(surface.page)
  }

  internal fun searchClosed(field: JTextField) {
    searchPopup = null
    searching = null
    retiredSearchField = field
    if (!finished) focus.request(surface.host, {}, { finish(false) })
  }

  private fun inSearch(component: Component?): Boolean =
    component != null && searching?.let { component == it || SwingUtilities.isDescendingFrom(component, it) } == true

  private fun render() {
    if (finished || invalidated) return
    val bounds = SwingUtilities.convertRectangle(surface.page.parent, surface.page.bounds, layeredPane)
    val resized = overlay.bounds != bounds
    val previousCursor = overlayCursor
    val previousStatus = overlayStatus
    val cursor = try {
      val textBounds = surface.parts[controller.cursor.part].cursorBounds(controller.cursor.offset)
      SwingUtilities.convertRectangle(surface.host, textBounds, layeredPane).apply { translate(-bounds.x, -bounds.y) }
    } catch (_: Exception) {
      invalidate()
      return
    }
    overlayCursor = cursor
    overlayStatus = controller.status
    if (resized) {
      overlay.bounds = bounds
      overlay.repaint()
    } else {
      if (previousCursor != cursor) {
        previousCursor?.let { overlay.repaint(it) }
        overlay.repaint(cursor)
      }
      if (previousStatus != overlayStatus) overlay.repaint(0, overlay.height - 26, overlay.width, 26)
    }
  }

  fun finish(restoreFocus: Boolean) {
    if (finished) return
    val returnFocus = restoreFocus && (focus.owner == surface.host || inSearch(focus.owner))
    finished = true
    Disposer.dispose(dispatcherLifetime)
    focusManager.removePropertyChangeListener("focusOwner", focusListener)
    Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener)
    surface.page.removeHierarchyListener(hierarchyListener)
    surface.parts.forEach { it.component.document.removeDocumentListener(documentListener) }
    scrollListeners.forEach { (viewport, listener) -> viewport.removeChangeListener(listener) }
    searchPopup?.cancel()
    retiredSearchField = null
    layeredPane.remove(overlay)
    layeredPane.repaint()
    try {
      surface.close(returnFocus && !invalidated)
    } finally {
      surface.host.isFocusable = originalFocusable
      surface.host.isRequestFocusEnabled = originalRequestFocusEnabled
      onClose()
      if (returnFocus && originalFocus?.isShowing == true) focus.request(originalFocus, {}, {})
    }
  }
}
