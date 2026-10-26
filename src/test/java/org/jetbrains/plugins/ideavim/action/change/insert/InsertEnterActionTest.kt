/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.action.change.insert

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.newapi.IjNativeAction
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.state.mode.Mode
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.plugins.ideavim.SkipNeovimReason
import org.jetbrains.plugins.ideavim.TestWithoutNeovim
import org.jetbrains.plugins.ideavim.VimTestCase
import org.junit.jupiter.api.Test

class InsertEnterActionTest : VimTestCase() {
  @Test
  fun `test native Enter lookup starts at the content component`() {
    configureByText("${c}text")
    val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
    val action = DumbAwareAction.create { _: AnActionEvent -> }
    action.registerCustomShortcutSet(CustomShortcutSet(enter), fixture.editor.contentComponent, fixture.testRootDisposable)

    val actions = VimPlugin.getKey().getActions(fixture.editor.vim, enter)

    assertSame(action, (actions.first() as IjNativeAction).action)
  }

  @Test
  fun `test native Enter lookup preserves ancestor handlers after content handlers`() {
    configureByText("${c}text")
    val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
    val contentAction = DumbAwareAction.create { _: AnActionEvent -> }
    val parentAction = DumbAwareAction.create { _: AnActionEvent -> }
    parentAction.registerCustomShortcutSet(CustomShortcutSet(enter), fixture.editor.component, fixture.testRootDisposable)
    contentAction.registerCustomShortcutSet(CustomShortcutSet(enter), fixture.editor.contentComponent, fixture.testRootDisposable)

    val actions = VimPlugin.getKey().getActions(fixture.editor.vim, enter).map { (it as IjNativeAction).action }

    assertSame(contentAction, actions.first())
    assertTrue(actions.indexOf(parentAction) > actions.indexOf(contentAction))
  }

  @Test
  fun `test insert enter`() {
    val before = """Lorem ipsum dolor sit amet,
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    val after = """Lorem ipsum dolor sit amet,
        |
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    doTest(listOf("i", "<Enter>"), before, after, Mode.INSERT)
  }

  @Test
  fun `test insert enter multicaret`() {
    val before = """Lorem ipsum dolor sit amet,
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |${c}Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    val after = """Lorem ipsum dolor sit amet,
        |
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |
        |${c}Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    doTest(listOf("i", "<Enter>"), before, after, Mode.INSERT)
  }

  @TestWithoutNeovim(SkipNeovimReason.CTRL_CODES)
  @Test
  fun `test insert enter with C-M`() {
    val before = """Lorem ipsum dolor sit amet,
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    val after = """Lorem ipsum dolor sit amet,
        |
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    doTest(listOf("i", "<C-M>"), before, after, Mode.INSERT)
  }

  @TestWithoutNeovim(SkipNeovimReason.CTRL_CODES)
  @Test
  fun `test insert enter with C-J`() {
    val before = """Lorem ipsum dolor sit amet,
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    val after = """Lorem ipsum dolor sit amet,
        |
        |${c}consectetur adipiscing elit
        |Sed in orci mauris.
        |Cras id tellus in ex imperdiet egestas.
    """.trimMargin()
    doTest(listOf("i", "<C-J>"), before, after, Mode.INSERT)
  }

  @TestWithoutNeovim(SkipNeovimReason.OPTION)
  @Test
  fun `test insert enter scrolls view up at scrolloff`() {
    configureByLines(50, "Lorem ipsum dolor sit amet,")
    enterCommand("set scrolloff=10")
    setPositionAndScroll(5, 29)
    typeText("i", "<Enter>")
    assertPosition(30, 0)
    assertVisibleArea(6, 40)
  }
}
