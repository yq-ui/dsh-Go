# Operit root capability integration status

This file tracks the DSH Go integration requested for Operit-derived Android root capabilities. It is a capability map, not a claim that the projects are merged or equivalent.

## Current slice: Android host root Shell

| Capability | DSH Go integration | Verification status |
|---|---|---|
| Select root separately from Shizuku / self-ADB | Android Shell backend selector has a Root (su) option; disabled remains the default feature state. | Source wired; full Flutter test not run in this workspace. |
| Ask the root manager for app authorization | `su -c` is started only on an explicit user connect action; command calls reuse that live link and probe UID on the command channel. Root-manager UI is platform-mediated. | Not tested on device. |
| Prove execution identity | Root channel accepts the app-process peer only when its probe reports UID 0 and the current protocol revision. The tool admission path separately rejects any root backend whose UID is not 0. | UID policy unit test passes; actual root UID requires device test. |
| Run a host command | Existing bounded Android Shell job service executes `/system/bin/sh -c` in the selected backend process, returning bounded text, exit status, timeout/cancel information. | Existing execution contract reused; Root path not exercised on device. |
| Reach host command from the Linux guest | The guest already exposes fixed `mcp__phone__android_shell`; choose Root in Android Shell settings. PRoot UID 0 remains distinct from host UID 0. Added a guest contract test for the fixed MCP server/stdio relay and the explicit host-vs-guest wording. | Static contract test added; end-to-end guest-to-host test not run. |
| Stop / revoke live session | Stop/disable invalidates the lease, closes the root process and discards its channel. Root-manager authorization itself remains owned by the manager. | Lifecycle implementation added; device cancellation not tested. |
| Read-only system-partition metadata | New `root_device_info` tool permits non-recursive `list` and single-path `metadata` only under canonical `/system`, `/system_ext`, `/vendor`, `/product` or `/odm`; an already verified, live UID-0 Root connection and separate per-read native confirmation are required. | Source and fixture tests added but unrun; no on-device verification. This is a narrow capability, not Operit device-info or filesystem parity. |

## Related implementation areas

- `phone/shell/RootShellCommand.kt`: fixed `su -c app_process` argv construction and APK classpath quoting.
- `phone/shell/ShellRootBackend.kt`: explicit root connection lifecycle, pending-process cleanup and UID-0 probe.
- `phone/shell/ShellLink.kt`: framed stdin/stdout transport reused by the root process.
- `phone/shell/AndroidShellController.kt`: backend selection, root connection and existing command job admission; `phone/RootDeviceInfoTool.kt`: fixed-partition path parsing and generated read-only list/metadata commands.
- `phone/PhoneAndroidShell.kt` / `phone/PhonePlugin.kt`: per-command confirmation and native method-channel validation.
- `lib/phone/android_shell_channel.dart` / `lib/ui/phone/android_shell_page.dart`: Flutter Root option and transport state. The Dart parser now refuses `ready=true` for a disabled shell or a Root report missing UID 0 / connected-channel evidence; contradictory-state regression cases are present but await Flutter execution.
- `phone/PhoneGuest.kt`: guest instructions distinguish PRoot root from Android host root.

## Operit capability map

The shared-storage source snapshot's root wrapper inventory previously motivated a partial command-path audit. This continuation adds a narrow read-only device-information primitive: fixed system-partition first-level listing and single-path `ls -ld` metadata, gated on an already verified, live UID-0 Root connection and separate native confirmation for each read. The app's minimum Android API is 26; AOSP Android 8 Toybox includes `readlink -f` and `ls -a/-d/-l`, but OEM images, path permissions and SELinux can still affect execution. The generated command uses Kotlin `\$` escapes so the runtime shell receives command substitutions such as `target=$(...)`; source inspection confirms one backslash before each escaped dollar. If a listed partition canonicalizes to `/` (merged-root layout), the request fails closed with exit 77 rather than broadening access. The MCP inspection path now requires a previously explicit, live Root connection and will not launch `su`; it reuses that verified UID-0 channel, avoiding a second short-timeout identity probe after root-manager authorization. This is not a runtime test on Android. It does not implement Operit's broader device-information or filesystem interfaces.

Operit's `ToolGetter` declares twenty tool factories in this snapshot (nineteen executor/tool classes plus the calculator singleton; the matrix below keeps FFmpeg, FFmpeg info and FFmpeg convert as separate families). Only four getters branch on the selected Android permission level: filesystem, UI, system operations, and device information. Root wrappers for filesystem, system operations, and device information inherit Admin/Debugger implementations without adding overrides. `RootUITools` overrides seven operations (`tap`, `longPress`, `swipe`, `clickElement`, `setInputText`, `pressKey`, `getPageInfo`), uses `input` / `uiautomator`, and explicitly sets `uiShellIdentity = ShellIdentity.SHELL`; those UI operations do not by themselves prove UID 0. `getShellToolExecutor` returns `StandardShellToolExecutor`, not a root variant. A ROOT preference therefore does not imply all Operit tools run as root.

| ToolGetter family | Permission selection in Operit source | DSH Go comparison in this checkout |
|---|---|---|
| File system | Standard / Accessibility / Debugger / Admin / Root | DSH has the private Linux guest FS and workspace APIs; Android shared storage is separately permission-bound. Root path-scoped file APIs are not ported. |
| Shell tool executor | Always Standard | DSH Android Shell supports Shizuku, self-ADB, and the new explicit root `su` backend; it is a separate bounded/non-interactive command channel. |
| UI tools | Standard / Accessibility / Debugger / Admin / Root | DSH has an independent screen/tree/accessibility action model. Coordinate tap/long-press/swipe and key/text actions overlap, but it is not API parity: there is no arbitrary key-code tool or Operit-style XML selector tool; DSH uses expiring screen IDs, safety gates and confirmations. Operit RootUITools explicitly uses ShellIdentity.SHELL, so those UI commands are shell-identity operations, not proof of UID 0. See the per-operation map below. |
| System operations | Standard / Accessibility / Debugger / Admin / Root | RootSystemOperationTools is an empty Admin wrapper; the inherited 7 debugger methods are `modifySystemSetting`, `getSystemSetting`, `installApp`, `uninstallApp`, `startApp`, `stopApp`, `getNotifications`. DSH maps only foreground app launch and an allowlisted Settings-screen handoff; it has no generic package install/uninstall, force-stop, arbitrary settings read/write or notification inbox API. The per-operation table below records each gap. |
| Device information | Standard / Accessibility / Debugger / Admin / Root | DSH exposes selected status/diagnostic fields and now a bounded Root-only partition listing/metadata tool. It is not an Operit-parity device-info catalog. |
| HTTP | Standard only | DSH has its own networking/tool surface; not a Root-specific capability. |
| Web visit | Standard only | DSH runtime/browser tools are separate implementations; no root selection in Operit source. |
| Browser session | Standard only | Different browser/session architecture; no root selection in Operit source. |
| Intent | Standard only | DSH's phone `intent` tool has its own explicit schema and dispatch. |
| Send broadcast | Standard only | Not identified as a root-specific feature; no like-for-like DSH phone tool was mapped here. |
| Terminal command | Standard only | DSH guest terminal is Linux/PRoot; Android Shell is separate host execution. |
| Music playback | Standard only | DSH phone-control surface has no matching root-specific feature identified. |
| Memory query | Standard only | DSH uses its own project/runtime memory architecture; not a device-root capability. |
| FFmpeg | Standard only | No dedicated equivalent mapped in the Android phone catalog; project media/runtime processing is separate. |
| FFmpeg info | Standard only | No dedicated equivalent mapped in the Android phone catalog. |
| FFmpeg convert | Standard only | No dedicated equivalent mapped in the Android phone catalog. |
| Calculator | Standard only | General app tool, not a root capability. |
| Workflow | Standard only | Different workflow engine and data contracts; not root-specific. |
| Chat manager | Standard only | Different conversation model and UI; not root-specific. |
| Software settings modify | Standard only | Different app settings surface; not a device-root capability. |

The matrix catalogs all 20 `ToolGetter` factories and labels each standard-only family with its current DSH overlap or known non-equivalence. DSH Go has not imported Operit-specific schemas for those families; this is not a promise of API-compatible or full application cloning. The root-specific file, system-operation and UI families are further broken down below.

### Root UI operation comparison

| Operit RootUITools operation | Observed Operit behavior | Closest DSH Go feature | Parity / boundary |
|---|---|---|---|
| `tap` / `longPress` | Shell-identity `input tap`; long press is a same-coordinate `input swipe` with fixed 800 ms duration; optional `-d display`. | `mcp__phone__tap` with `long`=true, or `tap_xy` with long gesture. | Similar gesture, different consent and targeting contract: DSH uses a current screen id and element index, safety policy and confirmation; Operit directly runs a shell action. DSH does not expose arbitrary display selection in these tool schemas. |
| `swipe` | `input swipe` with start/end coordinates and duration (default 300 ms), optional display. | `mcp__phone__tap_xy` with `action=swipe`, screenshot observation coordinates and duration (100–2000 ms). | Similar gesture only. DSH requires coordinates bound to a recent screenshot and applies its visual target/safety checks; it does not accept Operit's unrestricted `start_x/start_y/end_x/end_y` schema. |
| `clickElement` | Dumps UI hierarchy with uiautomator; matches resource id, class, content description, or bounds; supports index and partial matching, then taps selected node center. | `screen` + `tap` by returned element index; alternatively screenshot-bound `tap_xy`. | Not schema/selector parity. DSH exposes a filtered, expiring per-screen tree and index; there is no arbitrary XPath/resource-id/class selector API in the phone MCP catalog. Tree coverage also depends on accessibility or display capabilities, unlike the uiautomator dump path. |
| `setInputText` | Clears via `KEYCODE_CLEAR`, sets clipboard text, then pastes via `KEYCODE_PASTE`. | `type` replaces text in a selected field and returns the result/current screen. | Same broad outcome, different backend and policy: DSH uses its driver and identifies secret fields, masks them and subjects submission to explicit safety/confirmation rules; Operit uses clipboard/paste through shell identity. Do not copy the latter flow as UID-0 behavior. |
| `pressKey` | Accepts a key code and invokes `input keyevent`. | `key` exposes back/home/recents/notifications; text submit can press Enter. | Partial overlap. No general key-code dispatch in DSH's phone schema; extending it requires a constrained key allowlist, screen routing and safety/confirmation tests. |
| `getPageInfo` | uiautomator XML dump plus `dumpsys window/activity` parsing; emits package/activity and simplified nodes. | `screen` (accessibility/display tree, optional image) plus app/focus metadata. | Partial, non-equivalent data contract. DSH applies per-app read policy and filters protected content; no equivalent guaranteed XML dump or identical activity parsing. |

This comparison is source-level only; no Operit or DSH device execution was performed for this table. DSH tool schema and dispatch evidence: `phone/PhoneToolCatalog.kt`, `phone/PhoneCore.kt` and `phone/a11y/` implementation. Operit evidence: `RootUITools.kt` and its `AdminUITools` superclass (which adds no overrides).

### Operit Root system-operation comparison

| Operit RootSystemOperationTools inherited operation | Closest DSH Go capability | Coverage boundary |
|---|---|---|
| `getSystemSetting / modifySystemSetting` | `mcp__phone__intent(kind=settings, page=...)` opens a fixed allowlist of Settings screens. | DSH opens UI only; it has no typed read/write of arbitrary system setting values. Pages such as developer options, permissions, accessibility and notifications beyond one app's settings are deliberately outside the intent allowlist. |
| `installApp / uninstallApp` | DSH update/install flows apply to DSH's own APK; no general phone tool installs or uninstalls arbitrary packages. | Not equivalent. Do not substitute a raw privileged shell command for an Operit API without dedicated package identity, confirmation and result verification design. |
| `startApp` | `mcp__phone__apps` lists launchable apps; `mcp__phone__open(app=...)` prepares and dispatches launch through DSH's safety/confirmation flow. | Partial match for foreground launch only; no separate explicit start/stop API contract. |
| `stopApp` | No matching DSH phone tool. | Not ported; DSH has no general force-stop operation. |
| `getNotifications` | No general notification-list reader in the DSH phone MCP catalog. | Not ported; DSH's notification tool sends a notification and is not an inbox reader. |

Source review verified that `RootSystemOperationTools` is an empty subclass of `AdminSystemOperationTools` (and Root file/device-info wrappers likewise add no overrides); root-named wrappers therefore add no root-specific method implementation here. DSH evidence is in `PhoneToolCatalog.kt`, `PhoneCore.kt` and `SystemActions.kt`. This is a source/API comparison, not a device behavior test.

| Operit area | Current DSH Go status | Follow-up |
|---|---|---|
| RootShellExecutor / RootAuthorizer | Root connection + bounded command path added to DSH Android Shell, not a source import of Operit's libsu executor. | Exercise authorization, command, stderr/exit, reconnect and cancellation on real devices. |
| Operit filesystem operation family | `listFiles`, `readFileFull`, `readFile`, `readFilePart`, `writeFile`, `deleteFile`, `fileExists`, `moveFile`, `copyFile`, `makeDirectory`, `findFiles`, `fileInfo`, `zipFiles`, `unzipFiles`, `openFile`, `shareFile`, `downloadFile`. `RootFileSystemTools` is a thin inheritance wrapper here. | DSH has its own workspace/file APIs, but they are not an Operit-compatible full-device filesystem surface. Root shell is command-only, not typed/path-scoped parity. |
| Operit system-operation family | `modifySystemSetting`, `getSystemSetting`, `installApp`, `uninstallApp`, `startApp`, `stopApp`, `getNotifications`; `RootSystemOperationTools` is a thin wrapper. | DSH `intent` and `open` cover different operations. App installation is separately gated through the DSH update/install flow; no Operit-compatible system-operation schema bundle is ported. |
| Operit root UI overrides | `tap`, `longPress`, `swipe`, `clickElement`, `setInputText`, `pressKey`, `getPageInfo`; shell identity is explicitly `SHELL`. | Feature-by-feature source comparison is recorded above. Coordinate/text actions overlap, but selector, arbitrary key-code, target-display, confirmation, filtering and output contracts differ. No root-specific UI executor is ported; DSH's existing screen UI actions remain the host app's path. Static source assertions pass; runtime parity remains untested. |
| Operit device-information family | `RootDeviceInfoToolExecutor` is a thin wrapper over the debugger/accessibility implementation in this checkout. | DSH adds `root_device_info` for fixed-root listing/metadata only. Most Operit device-information fields/actions remain unmapped; no compatible executor or complete field map is implemented. |
| Operit model/chat/provider/UI features | Not copied wholesale. DSH Go remains the host app and keeps its own runtime, UI and provider architecture. | Continue inventory by capability and reuse only code with compatible license/dependency contracts. |

## Verification performed in this workspace

- Passed: isolated Kotlin 1.9.10 compilation of `RootShellCommand.kt` + its JUnit tests: `OK (2 tests)`; `ShellProtocol.kt` + its JUnit tests: `OK (8 tests)`. A combined run reported `OK (10 tests)`.
- Passed: isolated Kotlin compilation of `ShellRootBackend.kt`, `ShellLink.kt`, `ShellProtocol.kt` and `RootShellCommand.kt` against minimal Android/transport stubs; this validates source syntax and local call/type compatibility only, not the full Android app.
- Passed: six audit assertions count all 20 `ToolGetter` factories and 20 matrix rows; verify Root filesystem/system/device wrappers add no overrides; and ensure the UI/system per-operation tables are present. Source-text checks only, not runtime tests.
- Added but not run: Kotlin unit tests for `root_device_info` schema, path rejection, canonical-path allowlist command generation (including rejecting canonical `/`), shell quoting and per-invocation Root confirmation; Root UID/backend confirmation gates; Flutter regressions that force root readiness false for mismatched UID/channel status; and Android tests for guest MCP relay routing, explicit PRoot-vs-host wording, and DSH's screen-index / fixed-key-enum UI schema. No tests for the new slice were executed. The controller now admits Root metadata only through an already-established UID-0 Root channel; no MCP call can initiate `su` authorization.
- Not run: full Flutter analyze/tests or Gradle Android unit-test task. This checkout snapshot has no `pubspec.yaml`, Flutter SDK path/local.properties, `gradlew` script, or Gradle wrapper JAR; `flutter`, `dart`, `gradle` and `kotlinc` are absent from the current execution PATH.
- Not run: APK build, installation, root-manager approval, Root/Shell interaction on the phone, or Linux guest MCP-to-host-root round-trip.

## Known boundaries

1. Host root access is device/root-manager/SELinux-policy dependent; a successful connect must be demonstrated on the target phone, not inferred from a `su` binary.
2. Shared-storage visibility and arbitrary private app data are not promised by enabling root Shell. SELinux, mount namespaces and Android policy still matter.
3. The root command channel inherits existing Android Shell constraints (non-interactive, bounded command/output/time, native confirmation, no persistent background jobs). This is not a full shell terminal replacement inside DSH's UI.
4. Operit source has not been copied into DSH Go. The projects differ in app architecture and build setup; source reuse also requires a license/dependency review before importing code.
5. The broad objective remains in progress until the root path is verified on the device and the remaining Operit feature inventory is triaged and implemented or explicitly marked non-equivalent.
