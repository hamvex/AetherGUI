# Aethon Android Update Prompt

Implement the following changes in the existing Aethon Android project.

IMPORTANT:
- First inspect the existing codebase and architecture.
- Preserve all existing functionality.
- Reuse existing VPN service, connection logic, state management and UI architecture.
- Do not create duplicate VPN implementations.
- Do not perform unnecessary large-scale refactoring.
- Support Android 8–16.
- Build and test after implementation.

==================================================
1. ANDROID QUICK SETTINGS TILE
==================================================

Add an Android Quick Settings Tile named:

"Aethon VPN"

Behavior:
- If VPN is disconnected, tapping the tile connects using the existing VPN connection logic.
- If VPN is connected, tapping the tile disconnects using the existing VPN logic.
- Do not create a second VPN implementation.

Tile states:
- Connected → active
- Disconnected → inactive
- Cannot connect → unavailable

Keep the tile synchronized with the real VPN state.

Update the tile after:
- Connection
- Disconnection
- VPN failure
- Auto reconnect
- Smart Connect protocol switching
- Any VPN state change

Implementation:
- Create the required TileService.
- Register it in AndroidManifest.xml.
- Implement onStartListening().
- Implement onStopListening().
- Implement onClick().
- Reuse the existing VPN service/state manager.
- Do not block the UI thread.
- Handle VPN permission/user interaction gracefully.
- If required, open the appropriate Aethon screen instead of crashing.
- Support Android 8–16.
- Add appropriate tests.

Use the official Aethon monochrome Quick Settings icon.
Do not use Android system download icons.

The tile must never show Connected after the VPN has actually disconnected.

==================================================
2. PERSIAN LANGUAGE + RTL
==================================================

Add complete Persian language support.

Languages:
- English
- فارسی

Requirements:
- Use Android string resources.
- Do not hardcode UI strings.
- Translate all user-facing text, including:
  - Dialogs
  - Buttons
  - Menus
  - Smart Connect
  - Split Tunneling
  - Protocols
  - Benchmark
  - Ranking
  - Connection status
  - Diagnostics
  - Settings
  - Errors
  - Quick Settings related text where applicable

Add proper RTL support.

Fix:
- Text alignment
- Icon alignment
- Control positioning
- Margins
- Padding
- Spacing
- Typography

Support Persian typography and appropriate Persian digits.

English LTR must remain correct.

Persist the selected language.

Changing language must correctly update the UI.

==================================================
3. DIAGNOSTICS LIVE LOGS
==================================================

Fix the Diagnostics log viewer.

Current problem:
Logs are not displayed smoothly and in real time.

Requirements:
- Display logs immediately as they are generated.
- Minimize latency.
- Keep scrolling smooth.
- Automatically follow the newest logs when appropriate.
- Never freeze or lag the UI.
- Never block the main thread.
- Handle large log volumes efficiently.
- Prevent unbounded memory usage.
- Use appropriate Coroutine / Flow / streaming architecture.
- Preserve all existing Diagnostics functionality.

The result should behave like a smooth live console.

==================================================
4. HEADER REDESIGN
==================================================

Redesign the top header.

Remove completely:

"Aether 1.5.0 • Android"

Requirements:
- Modern
- Minimal
- Clean
- Proper spacing
- Proper alignment
- Preserve Aethon branding
- Support Light Mode
- Support Dark Mode
- Support Persian RTL
- Support English LTR

Also remove the Diagnostics icon from the top header.

IMPORTANT:
Do NOT remove the Diagnostics feature itself.

Rebalance the header after removing the icon.

==================================================
5. MAIN DASHBOARD TEXT OVERLAP
==================================================

Fix text overlap between:

TRAFFIC
PROTOCOL
MODE

and their corresponding values.

Requirements:
- No text overlap.
- No value overlap.
- Proper spacing.
- Consistent alignment.
- Handle long protocol names.
- Use controlled wrapping or truncation when necessary.
- Responsive layout.
- Support different screen sizes.
- Support different font sizes.
- Support English LTR.
- Support Persian RTL.
- Support Light Mode.
- Support Dark Mode.

Do not simply make the text extremely small.

==================================================
6. CONNECTION STATUS INDICATOR
==================================================

The circular connection/status indicator on the main connection card is too small.

Make it noticeably larger.

Requirements:
- Larger size.
- More visually prominent.
- Proper padding.
- No overlap with other elements.
- Maintain visual balance.
- Support connected/disconnected states.
- Support Light Mode.
- Support Dark Mode.
- Support Persian RTL.

==================================================
7. DEFAULT SCAN MODE
==================================================

Default Scan Mode:

Turbo

Requirements:
- Fresh installation → Turbo.
- First launch → Turbo.
- Persist user selection.
- If user selects another mode, do not reset it to Turbo on future launches.

==================================================
8. DEFAULT PROTOCOL
==================================================

Default Protocol:

gool / WARP-in-WARP

Requirements:
- Fresh installation → this protocol.
- First launch → this protocol.
- Persist user selection.
- Never overwrite the user's selected protocol on future launches.

==================================================
9. SPLIT TUNNELING CHECKBOX BUG
==================================================

Fix the Split Tunneling selection bug.

Current problem:
When the user taps the checkbox beside an app, the list scrolls instead of selecting the app.

Requirements:
- Checkbox tap must select/deselect the app.
- Checkbox tap must NOT scroll the list.
- Fix touch event propagation.
- Correctly separate checkbox taps from drag gestures.
- Selection must be immediate and smooth.
- Scrolling must still work normally.
- Do not select apps accidentally while scrolling.
- Test different screen sizes.
- Test Persian RTL.
- Test Light/Dark themes.

==================================================
10. DIAGNOSTICS UI CLEANUP
==================================================

Inside Diagnostics remove:

"Run connection test"

"Test the proxy and inspect live Aether output"

Do NOT remove:
- Connection test functionality
- Live logs
- Diagnostics

After removing the text:
- Reorganize spacing.
- Remove unnecessary empty space.
- Keep the layout clean and compact.
- Support Light/Dark.
- Support Persian RTL.

==================================================
11. MAIN CONNECTION CARD
==================================================

Remove:

"Ready when you are"

Do not remove:
- Connection status
- Connect button
- Other useful connection information

After removing it:
- Reorganize spacing.
- Remove unnecessary empty space.
- Keep the card balanced.
- Support Light/Dark.
- Support Persian RTL.

==================================================
12. NOTIFICATION ICON
==================================================

Fix the Android VPN foreground notification icon.

Current problem:
The notification shows the Android download icon.

Requirements:
- Do NOT use android.R.drawable.stat_sys_download.
- Do NOT use any Android download/system icon.
- Use a dedicated Aethon monochrome notification icon.
- White monochrome foreground.
- Transparent background.
- Follow Android notification icon guidelines.
- Support Android 8–16.
- Keep the launcher icon unchanged.
- Ensure the VPN foreground service uses the correct Aethon notification icon.

==================================================
13. UI / UX
==================================================

After all changes:

- Consistent spacing.
- Consistent typography.
- No overlapping elements.
- No clipped text.
- No unnecessary empty space.
- Proper touch targets.
- Responsive layouts.
- Material 3 consistency.
- Light Mode support.
- Dark Mode support.
- Persian RTL support.
- English LTR support.

Do not redesign unrelated areas.

==================================================
14. PERFORMANCE / STABILITY
==================================================

Requirements:
- No blocking main-thread operations.
- Use Kotlin Coroutines where appropriate.
- Use Flow / StateFlow where appropriate.
- Avoid memory leaks.
- Avoid unnecessary recompositions/redraws.
- Keep live logs efficient.
- Avoid ANRs.
- Handle VPN lifecycle correctly.
- Handle Quick Settings lifecycle correctly.
- Preserve existing VPN stability.

==================================================
15. TESTING
==================================================

Test all of the following:

QUICK SETTINGS:
- Add Tile
- Connect
- Disconnect
- Connected state
- Disconnected state
- Unavailable state
- VPN permission handling
- Connection failure
- Auto reconnect
- Smart Connect switching
- State synchronization

LANGUAGE:
- Persian
- English
- RTL
- LTR
- Language persistence

DIAGNOSTICS:
- Real-time logs
- Smooth scrolling
- Large log volume
- No UI freezing

UI:
- Header redesign
- Diagnostics icon removal
- Text overlap fix
- Larger connection indicator
- Main connection card
- Removed texts
- Light Mode
- Dark Mode
- Persian RTL

DEFAULTS:
- Scan Mode = Turbo on fresh install
- Protocol = gool / WARP-in-WARP on fresh install
- User selections persist

SPLIT TUNNELING:
- Checkbox selection
- Checkbox does not scroll
- Normal list scrolling
- RTL behavior

NOTIFICATION:
- Correct Aethon icon
- No download icon

==================================================
16. FINAL VERIFICATION
==================================================

Before finishing:

1. Inspect all modified code.
2. Build the Android project.
3. Run all relevant tests.
4. Fix compilation errors.
5. Fix runtime errors.
6. Fix relevant lint issues.
7. Verify Android 8–16 compatibility.
8. Verify Light Mode.
9. Verify Dark Mode.
10. Verify Persian RTL.
11. Verify English LTR.
12. Verify existing VPN functionality.

At the end report:

1. Modified files.
2. New files.
3. Implemented features.
4. Fixed bugs.
5. Tests performed.
6. Remaining issues or limitations.
7. Confirm that the existing VPN implementation was reused and no duplicate VPN implementation was created.

Do not claim anything is implemented unless it actually exists and works in the codebase.
