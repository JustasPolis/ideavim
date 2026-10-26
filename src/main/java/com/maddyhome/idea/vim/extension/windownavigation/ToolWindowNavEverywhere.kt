/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.windownavigation

import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.helper.EditorHelper
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal class ToolWindowNavEverywhere : VimExtension {
  companion object {
    const val PLUGIN_NAME = "ToolWindowNavEverywhere"
  }

  override fun getName() = PLUGIN_NAME

  private var registeredFocusOwner: JComponent? = null

  val focusListener = PropertyChangeListener { evt ->
    updateRegistration(evt.newValue as? JComponent)
  }

  private fun updateRegistration(focusOwner: JComponent?) {
    if (focusOwner === registeredFocusOwner) return

    val dispatcher = service<ToolWindowNavDispatcher>()

    registeredFocusOwner?.let(dispatcher::unregisterCustomShortcutSet)
    if (focusOwner != null && isInsideToolWindow(focusOwner) && !isPythonConsoleComponent(focusOwner)) {
      dispatcher.register(focusOwner)
      registeredFocusOwner = focusOwner
    } else {
      registeredFocusOwner = null
    }
  }

  override fun init() {
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    focusManager.addPropertyChangeListener("focusOwner", focusListener)
    updateRegistration(focusManager.focusOwner as? JComponent)
  }

  override fun dispose() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
      .removePropertyChangeListener("focusOwner", focusListener)
    registeredFocusOwner?.let(service<ToolWindowNavDispatcher>()::unregisterCustomShortcutSet)
    registeredFocusOwner = null
    super.dispose()
  }

  private fun isPythonConsoleComponent(component: Component): Boolean {
    for (project in ProjectManager.getInstance().openProjects) {
      if (project.isDisposed) continue
      val toolWindowManager = ToolWindowManager.getInstance(project)
      val tw = toolWindowManager.getToolWindow(EditorHelper.PYTHON_CONSOLE_TOOL_WINDOW_ID) ?: continue
      if (SwingUtilities.isDescendingFrom(component, tw.component)) {
        return true
      }
    }
    return false
  }

  private fun isInsideToolWindow(component: Component): Boolean {
    for (project in ProjectManager.getInstance().openProjects) {
      if (project.isDisposed) continue
      val toolWindowManager = ToolWindowManager.getInstance(project)
      for (id in toolWindowManager.toolWindowIds) {
        if (id == EditorHelper.PYTHON_CONSOLE_TOOL_WINDOW_ID) continue
        val toolWindow = toolWindowManager.getToolWindow(id) ?: continue
        if (!toolWindow.isVisible) continue
        if (toolWindow.type == ToolWindowType.FLOATING || toolWindow.type == ToolWindowType.WINDOWED) continue
        if (SwingUtilities.isDescendingFrom(component, toolWindow.component)) {
          return true
        }
      }
    }
    return false
  }
}
