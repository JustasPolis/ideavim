package org.jetbrains.plugins.ideavim.extension.nerdtree

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.extension.nerdtree.NerdTreeEverywhere
import com.maddyhome.idea.vim.extension.nerdtree.createEverywhereMappings
import org.jetbrains.plugins.ideavim.VimTestCase
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.event.KeyEvent
import java.beans.PropertyChangeEvent
import javax.swing.KeyStroke
import javax.swing.JTextField
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NerdTreeEverywhereTest : VimTestCase() {
  @Test
  fun `tree already focused when enabled receives navigation immediately`() = onEdt {
    val tree = sampleTree()
    val manager = object : DefaultKeyboardFocusManager() {
      override fun getFocusOwner(): Component = tree
    }
    val extension = NerdTreeEverywhere(manager)
    val dispatcher = service<NerdTreeEverywhere.Dispatcher>()
    extension.init()
    try {
      assertTrue(ActionUtil.getActions(tree).contains(dispatcher))
    } finally {
      extension.dispose()
    }
    assertFalse(ActionUtil.getActions(tree).contains(dispatcher))
    assertFalse(manager.getPropertyChangeListeners("focusOwner").contains(extension.focusListener))
  }

  @Test
  fun `focus moves unregister the previous tree including a temporary null owner`() = onEdt {
    val extension = NerdTreeEverywhere()
    val dispatcher = service<NerdTreeEverywhere.Dispatcher>()
    val first = sampleTree()
    val second = sampleTree()
    try {
      focus(extension, first)
      focus(extension, second)
      assertFalse(ActionUtil.getActions(first).contains(dispatcher))
      assertTrue(ActionUtil.getActions(second).contains(dispatcher))
      focus(extension, JTextField())
      assertFalse(ActionUtil.getActions(second).contains(dispatcher))
      focus(extension, second)
      focus(extension, null)
      assertFalse(ActionUtil.getActions(second).contains(dispatcher))
    } finally {
      extension.dispose()
    }
  }

  @Test
  fun `repeat focus notifications keep navigation registered`() = onEdt {
    val extension = NerdTreeEverywhere()
    val tree = sampleTree()
    try {
      focus(extension, tree)
      focus(extension, tree)
      assertTrue(ActionUtil.getActions(tree).contains(service<NerdTreeEverywhere.Dispatcher>()))
    } finally {
      extension.dispose()
    }
  }

  @Test
  fun `hjkl delegate to native selection and expansion`() = onEdt {
    val tree = sampleTree()
    tree.setSelectionRow(0)
    tree.perform("j")
    assertEquals(1, tree.selectionRows.single())
    tree.perform("k")
    assertEquals(0, tree.selectionRows.single())
    tree.collapseRow(1)
    tree.setSelectionRow(1)
    tree.perform("l")
    assertTrue(tree.isExpanded(1))
    tree.perform("l")
    assertEquals(2, tree.selectionRows.single())
    tree.perform("h")
    assertEquals(1, tree.selectionRows.single())
    tree.perform("h")
    assertFalse(tree.isExpanded(1))
  }

  @Test
  fun `gg and G select the first and last visible nodes`() = onEdt {
    val tree = sampleTree()
    tree.perform("G")
    assertEquals(tree.rowCount - 1, tree.selectionRows.single())
    tree.perform("gg")
    assertEquals(0, tree.selectionRows.single())
  }

  @Test
  fun `o invokes the tree native Enter binding for non-file session nodes`() = onEdt {
    val tree = sampleTree()
    var opened = 0
    tree.registerKeyboardAction({ opened++ }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), Tree.WHEN_FOCUSED)
    tree.setSelectionRow(1)
    tree.perform("o")
    assertEquals(1, opened)
    assertEquals(1, tree.selectionRows.single())
  }

  @Test
  fun `navigation stands down for search text and tree editors`() = onEdt {
    val dispatcher = service<NerdTreeEverywhere.Dispatcher>()
    val tree = sampleTree()
    val normalContext = SimpleDataContext.builder().add(PlatformDataKeys.CONTEXT_COMPONENT, tree).build()
    val normalEvent = TestActionEvent.createTestEvent(dispatcher, normalContext)
    dispatcher.update(normalEvent)
    assertTrue(normalEvent.presentation.isEnabled)
    val searching = SimpleDataContext.builder()
      .add(PlatformDataKeys.CONTEXT_COMPONENT, tree)
      .add(PlatformDataKeys.SPEED_SEARCH_TEXT, "session")
      .build()
    val searchEvent = TestActionEvent.createTestEvent(dispatcher, searching)
    dispatcher.update(searchEvent)
    assertFalse(searchEvent.presentation.isEnabled)
    val editing = object : Tree() {
      override fun isEditing() = true
    }
    for (component in listOf(editing, JTextField())) {
      val context = SimpleDataContext.builder().add(PlatformDataKeys.CONTEXT_COMPONENT, component).build()
      val event = TestActionEvent.createTestEvent(dispatcher, context)
      dispatcher.update(event)
      assertFalse(event.presentation.isEnabled)
    }
  }

  @Test
  fun `changing focused trees resets a pending gg sequence`() = onEdt {
    val extension = NerdTreeEverywhere()
    val dispatcher = service<NerdTreeEverywhere.Dispatcher>()
    val first = sampleTree()
    val second = sampleTree()
    second.setSelectionRow(2)
    try {
      focus(extension, first)
      dispatcher.actionPerformed(event(first, 'g'))
      focus(extension, second)
      dispatcher.actionPerformed(event(second, 'g'))
      assertEquals(2, second.selectionRows.single())
      dispatcher.actionPerformed(event(second, 'g'))
      assertEquals(0, second.selectionRows.single())
    } finally {
      extension.dispose()
    }
  }

  private fun focus(extension: NerdTreeEverywhere, component: Component?) {
    extension.focusListener.propertyChange(
      PropertyChangeEvent(this, "focusOwner", null, component),
    )
  }

  private fun sampleTree(): Tree {
    val root = DefaultMutableTreeNode("workspace")
    val group = DefaultMutableTreeNode("sessions")
    group.add(DefaultMutableTreeNode("first session"))
    group.add(DefaultMutableTreeNode("second session"))
    root.add(group)
    root.add(DefaultMutableTreeNode("another session"))
    return Tree(DefaultTreeModel(root)).apply {
      rowHeight = 20
      setSize(300, 200)
      TreeUtil.installActions(this)
      expandRow(0)
      expandRow(1)
    }
  }

  private fun event(tree: Tree, character: Char): AnActionEvent {
    val context = SimpleDataContext.builder().add(PlatformDataKeys.CONTEXT_COMPONENT, tree).build()
    val key = KeyEvent(tree, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, character)
    return AnActionEvent.createEvent(context, null, "test", ActionUiKind.NONE, key)
  }

  private fun Tree.perform(keys: String) {
    val action = checkNotNull(createEverywhereMappings()[injector.parser.parseKeys(keys)])
    action.action(event(this, keys.last()), this)
  }

  private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(block)
}
