package org.jetbrains.plugins.ideavim.extension.chatcopy

import com.maddyhome.idea.vim.extension.chatcopy.ChatTextPreparation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTextPreparationTest {
  @Test
  fun `eight offscreen panels are rendered natively one per UI turn before activation`() {
    val state = Fixture(8)
    state.work.start()
    assertEquals(0, state.rendered)
    state.queue.removeFirst().invoke()
    assertEquals(1, state.rendered)
    assertFalse(state.ready)
    state.drain()
    assertEquals(8, state.rendered)
    assertTrue(state.ready)
    assertNull(state.failure)
  }

  @Test
  fun `fully rendered transcripts activate without any materialization calls`() {
    val state = Fixture(0)
    state.work.start()
    state.drain()
    assertTrue(state.ready)
    assertEquals(0, state.rendered)
  }

  @Test
  fun `moving focus away cancels preparation instead of activating or reclaiming focus`() {
    val state = Fixture(8)
    state.work.start()
    state.queue.removeFirst().invoke()
    state.allowed = false
    state.drain()
    assertEquals(1, state.rendered)
    assertFalse(state.ready)
    assertTrue(state.cancelled)
    assertNull(state.failure)
  }

  @Test
  fun `explicit cancellation makes queued work inert`() {
    val state = Fixture(8)
    state.work.start()
    state.work.cancel()
    state.drain()
    assertEquals(0, state.rendered)
    assertFalse(state.ready)
    assertTrue(state.cancelled)
  }

  @Test
  fun `native render failures are surfaced without activating mode`() {
    val state = Fixture(8)
    state.throwOnRender = true
    state.work.start()
    state.drain()
    assertNotNull(state.failure)
    assertFalse(state.ready)
    assertEquals(0, state.rendered)
  }

  @Test
  fun `a native panel that stays unbuilt fails rather than looping forever`() {
    val state = Fixture(1)
    state.staysUnbuilt = true
    state.work.start()
    state.drain()
    assertEquals(1, state.rendered)
    assertNotNull(state.failure)
    assertFalse(state.ready)
  }

  @Test
  fun `time budget bounds work without silently skipping panels`() {
    val state = Fixture(8)
    state.work.start()
    state.queue.removeFirst().invoke()
    state.now = 6_000_000_000L
    state.drain()
    assertEquals(1, state.rendered)
    assertFalse(state.ready)
    assertNotNull(state.failure)
  }

  private class Fixture(count: Int) {
    val queue = ArrayDeque<() -> Unit>()
    private val panels = MutableList(count) { Any() }
    var allowed = true
    var ready = false
    var cancelled = false
    var rendered = 0
    var now = 0L
    var failure: Exception? = null
    var throwOnRender = false
    var staysUnbuilt = false
    val work = ChatTextPreparation(
      pending = { panels.toList() },
      materialize = { panel ->
        if (throwOnRender) error("native rendering failed")
        rendered++
        if (!staysUnbuilt) panels.remove(panel)
      },
      canContinue = { allowed },
      ready = { ready = true },
      failed = { failure = it },
      cancelled = { cancelled = true },
      enqueue = { queue.addLast(it) },
      clock = { now },
    )

    fun drain() {
      while (queue.isNotEmpty()) queue.removeFirst().invoke()
    }
  }
}
