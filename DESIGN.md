# Toolscreen for iOS — Port Design Notes

Porting Toolscreen 1.4.7 (Windows) to iOS, targeting
[Amethyst](https://github.com/AngelAuraMC/Amethyst-iOS) (the PojavLauncher successor
that runs Minecraft: Java Edition on iOS/iPadOS).

Status: **design exploration — no code written yet.**

---

## 1. What the Windows tool actually is

Reverse-engineering the shipped binary (`Toolscreen-1.4.7-double-click-me.exe`,
PE32+ x86-64, 13 MB) shows it is **not** an external window utility. It is an
injector plus an in-process DLL:

| Evidence in binary | Meaning |
| --- | --- |
| `EasyInjectBundled`, `CreateRemoteThread`, `LoadLibraryW`, `] Injecting: ` | Injects a DLL into the Minecraft process |
| `MinHook` (Tsuda Kageyu), `MH_Initialize`, `hkwglSwapBuffers` | Detours `wglSwapBuffers` to draw every frame |
| `Dear ImGui 1.92.6`, `ImGui_ImplOpenGL3_*`, GLEW | GUI is drawn **inside the game's GL context** |
| `glViewport` / `glBindTexture` / `glBlitFramebuffer` / `glBindFramebuffer` hooks | Intercepts the game's own rendering to resize and clone regions |
| `JNI_GetCreatedJavaVMs` | Talks to the Minecraft JVM for game state |
| `ICoreWebView2ExecuteScriptCompletedHandler` | Browser overlays via WebView2 |
| `Windows Defender ... exclusions so ... injected DLLs` | Self-aware about AV friction |

Feature tabs, recovered from the embedded localization table (EN + pt-BR):
`General · Modes · EyeZoom · Mirrors · Images · Browsers · Windows · Inputs ·
Mouse · Hotkeys · Appearance · Settings · Profiles · Misc`, plus a
Ninjabrain Bot integration (594 localization keys — the largest single namespace).

The headline feature — "change the shape of the screen" — is `Modes`: named
presets carrying `game_width` / `game_height`, switched by hotkey, with a hard
gate on **Minecraft 1.13+** (i.e. the GLFW era). Thin/stretched windows for eye
throws, plus `EyeZoom` (a magnified clone of a screen region with a measurement
overlay) and `Mirrors` (capture zones re-rendered elsewhere, with colour matching).

### Why this matters for the port

**Almost none of the hard Windows machinery needs to be recreated on iOS.**
The injection, the MinHook detours, and the GL hooks all exist to solve one
problem: *Toolscreen does not own the game window.* On iOS, the launcher does —
and we can change the launcher.

---

## 2. The key finding: Amethyst reimplements GLFW in Java

Amethyst-iOS does not ship real GLFW. It ships a **pure-Java reimplementation**
at `JavaApp/src/lwjgl/org/lwjgl/glfw/GLFW.java`, backed by an Objective-C
surface. Two consequences:

**(a) `glfwSetWindowSize` is already a no-op stub.** It only records numbers in a
Java object and logs — it does not touch the render surface:

```java
// JavaApp/src/lwjgl/org/lwjgl/glfw/GLFW.java:1023
public static void glfwSetWindowSize(long window, int width, int height) {
    internalGetWindow(window).width  = width;
    internalGetWindow(window).height = height;
    System.out.println("GLFW: Set size for window " + window + ", ...");
}
```

So the obvious naive port ("just call glfwSetWindowSize") does nothing. The real
resolution lives in `mGLFWWindowWidth` / `mGLFWWindowHeight`, which back
`glfwGetFramebufferSize`, `glfwGetWindowSize`, `glfwGetMonitorWorkarea` **and**
the `GLFWVidMode` monitor record. Whatever those two ints say, *is* the screen,
as far as Minecraft is concerned.

**(b) There is exactly one function that sets them.** In
`Natives/SurfaceViewController.m:364`:

```objc
- (void)updateSavedResolution {
    resolutionScale = getPrefFloat(@"video.resolution") / 100.0;
    self.surfaceView.layer.contentsScale = self.screenScale * resolutionScale;

    physicalWidth  = roundf(self.surfaceView.frame.size.width  * self.screenScale);
    physicalHeight = roundf(self.surfaceView.frame.size.height * self.screenScale);
    windowWidth    = roundf(physicalWidth  * resolutionScale);
    windowHeight   = roundf(physicalHeight * resolutionScale);
    if ((windowWidth  % 2) != 0) --windowWidth;
    if ((windowHeight % 2) != 0) --windowHeight;
    CallbackBridge_nativeSendScreenSize(windowWidth, windowHeight);   // → Java
}
```

Width and height are *derived* from the view frame and a single uniform
`video.resolution` percentage. **Toolscreen's entire Modes feature is: break that
derivation and let a mode supply the two numbers independently.** That is a
choke point of roughly twenty lines, versus a DLL injector and four GL detours
on Windows.

Amethyst also already has the two supporting pieces we'd otherwise have to
build: a **customisable on-screen control system** (our replacement for global
hotkeys, which iOS does not have) and **external-display support**
(`SurfaceViewController+ExternalDisplay.m`).

---

## 3. Three delivery routes

### Route A — Fork Amethyst ("Toolscreen Edition")

Patch `updateSavedResolution` to consult an active mode; decouple the surface
view's frame from its superview; add a native SwiftUI/UIKit settings panel.

- **Pros** — full access to everything; no injection or swizzling; EyeZoom and
  Mirrors become straightforward Metal/GL work on a layer we own; the config UI
  is a real iOS panel instead of an ImGui window drawn into the game.
- **Cons** — users must run *your* build instead of stock Amethyst; needs a
  rebase discipline against upstream; needs macOS + Xcode (or a macOS CI runner)
  to produce IPAs.
- **Best for** — the real product.

### Route B — Dylib tweak injected into stock Amethyst

Method-swizzle `updateSavedResolution` from an injected dylib. This is the
closest philosophical match to the Windows architecture.

- **Pros** — stock launcher keeps updating independently; one small artefact.
- **Cons** — requires TrollStore or a jailbreak; swizzling a private method is
  brittle across launcher releases; still needs macOS tooling.
- **Best for** — users already on TrollStore who refuse to switch builds.

### Route C — A Fabric mod, with zero launcher changes

Run inside the JVM and drive the framebuffer size from Java: mixin into
Minecraft's `Window` (`framebufferWidth` / `framebufferHeight` / `getWidth()`)
and trigger `resizeDisplay()`, optionally poking the launcher's public static
`GLFW.internalWindowSizeChanged(...)`.

- **Pros** — works on **stock, unmodified Amethyst**; no Xcode, no macOS, no
  TrollStore; ships as a `.jar`; as a bonus it would also work on Amethyst for
  **Android** and on desktop.
- **Cons** — UI is limited to in-game Minecraft GUI; no browser/image overlays;
  must not fight the launcher's own resize events, which are dispatched
  natively rather than through the Java callback path.
- **Confidence** — promising but **unproven**; the framebuffer-size callback is
  registered and invoked natively (`nglfwSetFramebufferSizeCallback`), so
  whether a Java-side resize survives the next native resize event needs a
  one-day spike before anyone commits to this route.

### Recommendation

**Spike C first, build A for real.** Route C answers "does a thin window even
help on a touch device?" in days rather than weeks, with no build infrastructure
at all — and if it works it is independently shippable to every stock-Amethyst
user. Route A is where the full tool ends up. Keep the mode/config format
identical between them so a Route C config imports into Route A later.

---

## 4. Feature-by-feature port map

| Windows feature | iOS | Notes |
| --- | --- | --- |
| **Modes** (game_width/height) | **Direct** | The `updateSavedResolution` choke point. Core of the port. |
| Thin / stretched / tall presets | **Direct** | Renders into a sub-rect; remainder letterboxed black. |
| Mode hotkey switching | **Redesign** | iOS has no global hotkeys → bind to Amethyst's existing on-screen controls, hardware keyboard, or controller. |
| `Go Borderless` / `Auto-Borderless` | **N/A** | iOS apps are always fullscreen. Repurpose as "restore native aspect". |
| **EyeZoom** (magnified clone + overlay) | **Port (A/B)** | Framebuffer blit; Metal or GL on a layer we own. Hardest visual feature, and the most valuable one for practising eye throws — but out of reach for Route C, so it is the main reason to move to Route A. |
| **Mirrors** (capture zones, colour match) | **Port (A/B)** | Same machinery as EyeZoom; build second. |
| Browser overlays (WebView2) | **Swap** | WebView2 → `WKWebView`. Route A/B only. |
| Image overlays, cursor trail | **Port (A/B)** | Native overlay views — easier than the ImGui original. |
| Mouse sensitivity multiplier | **Port** | `Natives/input_bridge_v3.m` already scales by `resolutionScale`. |
| Caps-Lock suppression | **N/A** | No such concept. |
| Ninjabrain Bot integration | **Rethink** | Desktop Java app. Either talk to a PC instance over LAN, or reimplement the maths natively. Largest namespace in the original — scope carefully. |
| Config profiles / `config.toml` | **Port** | Keep the format PC-compatible so existing configs import. |
| Debug-info upload | **Port** | Straightforward. |
| ImGui in-game GUI | **Replace** | Native SwiftUI panel (Route A) or Minecraft GUI screen (Route C). |

### iOS-only opportunities

Things the Windows tool *cannot* do, which fall out of this architecture for free:

1. **Per-mode control layouts.** Amethyst's on-screen controls are already
   per-profile JSON. Switching into an eye-measuring mode could simultaneously
   swap to a stripped-down touch layout. No desktop equivalent exists.
2. **External display modes.** `SurfaceViewController+ExternalDisplay.m` already
   exists — run the stretched mode on an external monitor while the iPad shows
   controls.
3. **Native settings UI** — a proper iOS panel, editable while the game runs,
   instead of an overlay drawn into the GL context.

---

## 5. Constraints worth knowing before starting

- **Build tooling.** Routes A and B need macOS + Xcode. Amethyst ships a
  `Makefile`, so a GitHub Actions macOS runner producing unsigned IPAs is a
  realistic substitute if no Mac is available. Route C needs only a JDK.
- **JIT is unchanged.** Amethyst needs JIT for usable speed (TrollStore/jailbreak
  auto-enable it; AltStore/SideStore/StikDebug need a computer on iOS 17/18).
  The port neither helps nor hurts this.
- **No App Store path** for any route. Distribution is TrollStore / AltStore /
  SideStore / jailbreak only.
- **Minecraft 1.13+ only**, same as the original — the feature is inherently
  GLFW-era. MCSR's 1.16.1 is well within Amethyst's supported range.
- **Upstream drift.** Routes A and B both couple to launcher internals
  (`updateSavedResolution` is a private method). Budget for rebases.
- **Leaderboard rules do not constrain this.** These runs are not being
  submitted, so no speedrun.com / MCSR tool policy applies and feature scope is
  decided purely by usefulness. (If that ever changes, check the rules for the
  relevant category first — several features here would need re-examining.)

---

## 6. Suggested first milestone

A single hard-coded thin mode, toggled by one on-screen button, via Route C —
enough to answer three questions at once: does the framebuffer override survive
the launcher's native resize events, does the game render correctly into a
sub-rect, and is a thin window actually usable with touch controls.

Everything else in this document is downstream of that answer.
