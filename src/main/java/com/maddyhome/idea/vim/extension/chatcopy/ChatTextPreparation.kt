package com.maddyhome.idea.vim.extension.chatcopy

import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.SwingUtilities

internal class ChatTextPreparation(
  private val pending: () -> List<Any>,
  private val materialize: (Any) -> Unit,
  private val canContinue: () -> Boolean,
  private val ready: () -> Unit,
  private val failed: (Exception) -> Unit,
  private val cancelled: () -> Unit,
  private val enqueue: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
  private val clock: () -> Long = System::nanoTime,
) {
  private val rendered = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
  private var stopped = false
  private var startedAt = 0L

  fun start() {
    startedAt = clock()
    enqueue(::advance)
  }

  fun cancel() {
    if (stopped) return
    stopped = true
    cancelled()
  }

  private fun advance() {
    if (stopped) return
    try {
      if (!canContinue()) {
        cancel()
        return
      }
      val panel = pending().firstOrNull()
      if (panel == null) {
        stopped = true
        ready()
        return
      }
      if (rendered.size >= 256 || clock() - startedAt > 5_000_000_000L) {
        throw ChatCopyUnavailable("Chat rendering took too long; copy mode was not enabled. Try again after the chat settles.")
      }
      if (!rendered.add(panel)) {
        throw ChatCopyUnavailable("Air has not finished rendering a chat section; copy mode was not enabled. Try again shortly.")
      }
      materialize(panel)
      enqueue(::advance)
    } catch (failure: Exception) {
      stopped = true
      failed(failure)
    }
  }
}
