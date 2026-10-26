# IdeaVim Air fork

This branch maintains a small set of changes on top of official IdeaVim
`2.48.0-eap.3`, commit `d22e60e1292015967aa3a425cfbe1b5e81bec0a0`.
It retains the upstream plugin ID, `IdeaVIM`, and replaces the installed IdeaVim
plugin rather than adding a second one. Air's own plugin files are not modified.

Current build version: `2026.10.26.6`.

## Changes

- Air composer Enter forwarding starts at the editor content component so native
  submission handlers retain precedence.
- Editor/tool-window navigation requests focus and manages shortcut registrations
  as the focused component changes.
- The opt-in Air transcript copy mode navigates the existing rendered transcript,
  delegates selection and scrolling to native Swing/Air APIs, and does not open a
  replacement chat editor.
- Transcript vertical navigation skips zero-height HTML positions and horizontal
  rules instead of producing invisible cursor positions.
- VimEverywhere tree navigation includes `h`/`l`, initializes for an already
  focused tree, removes shortcuts when focus changes or the extension is disabled,
  and leaves active search and tree editing alone.

## Air sidebar

Enable the existing extension in your IdeaVim configuration:

```vim
set VimEverywhere
```

Focus Air's session tree, then use:

| Keys | Action |
| --- | --- |
| `j` / `k` | Next / previous visible row |
| `h` / `l` | Native Left / Right tree behavior: collapse/parent or expand/child |
| `gg` / `G` | First / last visible row |
| `Ctrl+U` / `Ctrl+D` | Half-page movement |
| `o` | Activate a non-file node through its existing Enter binding |
| Enter | Air's unchanged native activation |
| `/` | Existing tree speed search |
| `Ctrl+W`, then `h/j/k/l` | Existing VimEverywhere tool-window navigation |

Air's inspected `AgentSessionsTree` extends IntelliJ's standard `Tree`, so this
support uses the existing tree extension and native Swing action maps. It does
not depend on Air's private tree models or add destructive session actions.
The same navigation improvements apply to other standard IntelliJ trees.
Ordinary text fields and inline tree editors retain normal text input.

## Transcript copy mode

Focus Air's composer or transcript and invoke **IdeaVim: Chat Copy Mode**,
press `Ctrl+Shift+X`, or run `:action VimChatCopyMode`.
The transcript adapter is pinned to Air build `262.8665.512-mac-arm64` and refuses
unreviewed versions. It supports basic movement, counts, visual selection,
literal search, and copying; it is not a full second Vim engine.
Use `q` to exit. Normal exit restores the original scroll/following state.

## Build and verify

Use JDK 21 and the checked-in Gradle wrapper:

```sh
./gradlew --no-daemon -Pversion=2026.10.26.6 :test \
  --tests 'org.jetbrains.plugins.ideavim.action.change.insert.InsertEnterActionTest' \
  --tests 'org.jetbrains.plugins.ideavim.extension.chatcopy.*' \
  --tests 'org.jetbrains.plugins.ideavim.extension.nerdtree.*' \
  --tests 'org.jetbrains.plugins.ideavim.extension.hints.TableEverywhereTest'
./gradlew --no-daemon -Pversion=2026.10.26.6 :buildPlugin
```

Install the ZIP from `build/distributions/` using **Plugins → Install Plugin from
Disk**, then restart when requested. Preserve your previous plugin before a
manual filesystem installation, and never replace a plugin while the IDE is
running.

The regression tests exercise detached Swing trees, native action delegation,
text-input guards, shortcut lifecycle, and transcript behavior. They do not
establish live validation of every Air UI state or guarantee smooth rendering.

## Updating

Keep these changes on the custom branch rather than pushing them to upstream.
Review Air adapter compatibility and rerun the tests before adopting another
IdeaVim or Air version. Installing a stock IdeaVim update replaces this build
and removes its custom behavior.
