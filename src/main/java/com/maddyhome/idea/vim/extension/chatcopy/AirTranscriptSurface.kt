package com.maddyhome.idea.vim.extension.chatcopy

import com.intellij.openapi.editor.impl.EditorComponentImpl
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

internal class ChatCopyUnavailable(message: String) : IllegalStateException(message)

internal class AirChatScroll(private val pane: JScrollPane) {
  private val reveal = pane.javaClass.getMethod("reveal", Component::class.java, Rectangle::class.java)
  private val resumeFollowing = pane.javaClass.getMethod("resumeFollowingBottomIfReached", String::class.java)
  private val stopFollowing = pane.javaClass.getMethod("stopFollowingBottomForUserAction", String::class.java)
  private val initiallyFollowing = invoke(pane.javaClass.getMethod("isFollowingBottom")) as Boolean

  val pageHeight: Int get() = pane.viewport.extentSize.height.coerceAtLeast(100)

  fun reveal(source: Component, bounds: Rectangle) {
    invoke(reveal, source, bounds)
  }

  fun restoreFollowing() {
    invoke(if (initiallyFollowing) resumeFollowing else stopFollowing, "IdeaVim chat copy mode closed")
  }

  private fun invoke(method: Method, vararg arguments: Any): Any? = try {
    method.invoke(pane, *arguments)
  } catch (failure: InvocationTargetException) {
    throw ChatCopyUnavailable("Air could not scroll this transcript")
  }
}

internal class AirTranscriptSurface private constructor(
  val page: JComponent,
  val host: JComponent,
  override val parts: List<ChatTextPart>,
  private val nativeSelection: Any,
  private val selectionType: Class<*>,
  private val adapters: List<Any>,
  private val release: Method,
  private val currentComponents: () -> List<JTextComponent>?,
  private val scrolling: AirChatScroll,
) : ChatTranscript, AutoCloseable {
  private val initialCarets = parts.map {
    val caret = it.component.caret
    CaretState(it.component, caret.dot, caret.mark, caret.isSelectionVisible, caret.isVisible)
  }
  private val initialScrolls = mutableListOf<Pair<JScrollPane, Point>>().also { scrolls ->
    walk(page) { if (it is JScrollPane) scrolls += it to Point(it.viewport.viewPosition) }
  }
  override val pageHeight: Int get() = scrolling.pageHeight

  fun isCurrent(): Boolean = page.isShowing && currentComponents() == parts.map { it.component }

  override fun select(start: ChatPosition, exclusiveEnd: ChatPosition) {
    invoke(selectionType.getMethod("selectBetween", Point::class.java, Point::class.java), nativeSelection,
           parts[start.part].pointAt(start.offset), parts[exclusiveEnd.part].pointAt(exclusiveEnd.offset))
  }

  override fun clearSelection() {
    invoke(selectionType.getMethod("clear"), nativeSelection)
  }

  override fun selectedText(): String? = invoke(selectionType.getMethod("selectedText"), nativeSelection) as? String

  override fun reveal(position: ChatPosition) {
    val part = parts[position.part]
    scrolling.reveal(host, part.cursorBounds(position.offset))
  }

  override fun close() {
    close(restoreScroll = true)
  }

  fun close(restoreScroll: Boolean) {
    var failure: Exception? = null
    val cleanup = listOf<() -> Unit>({ clearSelection() }) +
      adapters.map { adapter -> { invoke(release, adapter); Unit } } +
      initialCarets.map { state -> { state.restore() } } +
      (if (restoreScroll) initialScrolls.map { (scroll, point) -> { scroll.viewport.viewPosition = point } } +
        listOf<() -> Unit>({ scrolling.restoreFollowing() }) else emptyList())
    cleanup.forEach { operation ->
      try {
        operation()
      } catch (exception: Exception) {
        if (failure == null) failure = exception else failure?.addSuppressed(exception)
      }
    }
    failure?.let { throw it }
  }

  private data class CaretState(
    val component: JTextComponent,
    val dot: Int,
    val mark: Int,
    val selectionVisible: Boolean,
    val visible: Boolean,
  ) {
    fun restore() {
      val caret = component.caret
      caret.setDot(mark.coerceAtMost(component.document.length))
      caret.moveDot(dot.coerceAtMost(component.document.length))
      caret.isSelectionVisible = selectionVisible
      caret.isVisible = visible
    }
  }

  companion object {
    const val SUPPORTED_VERSION = "262.8665.512-mac-arm64"
    private const val PAGE = "com.intellij.air.frontend.session.view.gui.ui.ChatSessionPagePanel"
    private const val PREFIX = "com.intellij.air.frontend.session.view.gui.ui."

    private data class NativeContext(
      val host: JComponent,
      val selection: Any,
      val collector: Any,
      val collect: Method,
    ) {
      fun parts(): List<*> = collect.invoke(collector, host) as List<*>
    }

    fun findPage(context: Component?): JComponent? {
      if (context == null) return null
      val ancestors = generateSequence(context) { it.parent }.toList()
      ancestors.firstOrNull { it.javaClass.name == PAGE }?.let { return it as JComponent }
      if (ancestors.none { it.javaClass.name.endsWith(".AirPromptEditorTextField") }) return null
      for (ancestor in ancestors.filterIsInstance<Container>()) {
        val pages = mutableListOf<JComponent>()
        walk(ancestor) { if (it.isShowing && it.javaClass.name == PAGE) pages += it as JComponent }
        if (pages.size == 1) return pages.single()
        if (pages.size > 1) return null
      }
      return null
    }

    private fun nativeContext(page: JComponent, version: String?): NativeContext {
      if (version != SUPPORTED_VERSION) {
        throw ChatCopyUnavailable("Chat copy mode needs an Air adapter review for version ${version ?: "unknown"}")
      }
      if (page.javaClass.name != PAGE || !page.isShowing) throw ChatCopyUnavailable("No active Air transcript")
      val support = page.javaClass.getDeclaredField("textSelection").let {
        if (!it.trySetAccessible()) throw ChatCopyUnavailable("Air selection API is unavailable")
        it.get(page) ?: throw ChatCopyUnavailable("Air transcript is still loading")
      }
      if (support.javaClass.getMethod("getHasSelection").invoke(support) == true) {
        throw ChatCopyUnavailable("Clear the existing chat selection before entering copy mode")
      }
      val selection = support.javaClass.getDeclaredMethod("getSelection").let {
        if (!it.trySetAccessible()) throw ChatCopyUnavailable("Air selection API is unavailable")
        it.invoke(support) ?: throw ChatCopyUnavailable("Air selection is unavailable")
      }
      val host = selection.javaClass.getDeclaredField("content").let {
        if (!it.trySetAccessible()) throw ChatCopyUnavailable("Air transcript container is unavailable")
        it.get(selection) as JComponent
      }
      val collectorType = page.javaClass.classLoader.loadClass(PREFIX + "util.TextComponentCollector")
      return NativeContext(
        host, selection, collectorType.getField("INSTANCE").get(null),
        collectorType.getMethod("collectParts", Container::class.java),
      )
    }

    fun pendingPanels(page: JComponent, version: String?): List<Any> {
      return nativeContext(page, version).parts().mapNotNull {
        when (it?.javaClass?.simpleName) {
          "Built" -> null
          "Unbuilt" -> requireNotNull(it.javaClass.getMethod("getPanel").invoke(it))
          else -> throw ChatCopyUnavailable("Air's transcript structure changed; copy mode was not enabled")
        }
      }
    }

    fun materialize(panel: Any) {
      panel.javaClass.getMethod("materialize").invoke(panel)
    }

    fun connect(page: JComponent, version: String?): AirTranscriptSurface {
      try {
        val loader = page.javaClass.classLoader
        val native = nativeContext(page, version)
        val host = native.host
        val scrollPane = generateSequence(host as Component) { it.parent }
          .firstOrNull { it.javaClass.name == PREFIX + "scroll.AutoScrollPane" } as? JScrollPane
          ?: throw ChatCopyUnavailable("Air's transcript scroll container is unavailable")
        val scrolling = AirChatScroll(scrollPane)
        var embeddedEditor = false
        walk(host) { if (it is EditorComponentImpl) embeddedEditor = true }
        if (embeddedEditor) throw ChatCopyUnavailable("In-place code-editor navigation is not supported yet")
        val collected = native.parts()
        if (collected.any { it?.javaClass?.simpleName != "Built" }) {
          throw ChatCopyUnavailable("Air is still rendering chat text; copy mode was not enabled. Try again shortly.")
        }
        val components = collected.map {
          requireNotNull(it).javaClass.getMethod("getComponent").invoke(it) as JTextComponent
        }.filter { it.document.length > 0 }
        if (components.isEmpty()) throw ChatCopyUnavailable("No rendered chat text is available")
        val textType = loader.loadClass(PREFIX + "selection.TranscriptTextComponent")
        val companion = textType.getField("Companion").get(null)
        val of = companion.javaClass.getMethod("of", JTextComponent::class.java)
        val adapters = components.map { requireNotNull(of.invoke(companion, it)) }
        val offsetAt = textType.getMethod("offsetAt", Point::class.java)
        val parts = components.zip(adapters).map { (component, adapter) ->
          ChatTextPart(component, host) { point -> (invoke(offsetAt, adapter, point) as Number).toInt() }
        }
        return AirTranscriptSurface(
          page, host, parts, native.selection, loader.loadClass(PREFIX + "selection.TranscriptTextSelection"),
          adapters, textType.getMethod("release"),
          {
            val current = native.parts()
            if (current.any { it?.javaClass?.simpleName != "Built" }) null
            else current.map { requireNotNull(it).javaClass.getMethod("getComponent").invoke(it) as JTextComponent }
              .filter { it.document.length > 0 }
          }, scrolling,
        )
      } catch (failure: ReflectiveOperationException) {
        throw ChatCopyUnavailable("Air's native transcript API changed; copy mode was not enabled")
      }
    }

    private fun invoke(method: Method, receiver: Any, vararg arguments: Any): Any? = try {
      method.invoke(receiver, *arguments)
    } catch (failure: InvocationTargetException) {
      throw ChatCopyUnavailable("Air could not navigate this text layout")
    }

    private fun walk(component: Component, visit: (Component) -> Unit) {
      visit(component)
      if (component is Container) component.components.forEach { walk(it, visit) }
    }
  }
}
