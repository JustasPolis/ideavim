package org.jetbrains.plugins.ideavim.extension.chatcopy

import com.intellij.openapi.keymap.KeymapManager
import com.maddyhome.idea.vim.VimPlugin
import org.jetbrains.plugins.ideavim.VimTestCase
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCopyShortcutTest : VimTestCase() {
  @Test
  fun `macOS binds physical control shift X instead of converting control to command`() {
    val mac = assertNotNull(KeymapManager.getInstance().getKeymap("Mac OS X 10.5+"))
    val key = KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
    assertTrue(mac.getActionIds(key).contains("VimChatCopyMode"))
  }

  @Test
  fun `all-Vim shortcut ownership does not claim the copy-mode activation key`() {
    configureByText("composer")
    enterCommand("sethandler a:vim")
    val key = KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
    assertFalse(VimPlugin.getKey().requiredShortcutKeys.any { it.keyStroke == key })
  }
}
