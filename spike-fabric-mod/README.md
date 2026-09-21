# Toolscreen Mobile — Route C spike

A Fabric mod that gives Minecraft a stretched / thin screen shape on iOS,
running on **stock, unmodified [Amethyst](https://github.com/AngelAuraMC/Amethyst-iOS)** —
no launcher fork, no dylib, no TrollStore requirement.

**Target: Minecraft 1.16.1 / Fabric / Java 17.**

This is the spike described in [`../DESIGN.md`](../DESIGN.md) §6. It exists to
answer one question: *does a Java-side framebuffer override survive the
launcher's native resize events?* Everything else is deliberately minimal.

---

## How it works

Toolscreen on Windows resizes the real OS window. That is not available on iOS:
Amethyst's `glfwSetWindowSize` is a stub that only records numbers
(`JavaApp/src/lwjgl/org/lwjgl/glfw/GLFW.java:1023`), and the true render size is
pushed from the native side in `SurfaceViewController.updateSavedResolution`
(`Natives/SurfaceViewController.m:364`).

So this mod does not resize anything. It **lies about the framebuffer size**
where Minecraft reads it (`WindowMixin`). Minecraft sizes its main render target
from those getters and blits that target back to screen using them again, so a
smaller reported size renders the game into a sub-rect of the real surface.

**The override is self-healing.** Amethyst delivers genuine resizes (rotation,
app resume, resolution slider) by queueing an `EVENT_TYPE_FRAMEBUFFER_SIZE`
event, drained in `pojavPumpEvents` on the next `glfwPollEvents`
(`Natives/input_bridge_v3.m:297`). That path updates the real fields and calls
Minecraft's resize callback, which re-reads our getters and gets the overridden
values again. No re-assert logic is needed — and because those events are only
queued on an actual change, there is no per-frame fight with the launcher.

`MinecraftClientMixin` polls a key each tick to cycle modes. Amethyst's
on-screen buttons emit GLFW key codes, so **a key code doubles as a touch
binding** — that is the replacement for Toolscreen's global hotkeys, which have
no iOS equivalent. No Fabric API dependency: loader + yarn only, so there is one
less version to pin and one less jar to move onto the device.

## Configuration

First launch writes `config/toolscreen-mobile.properties`:

```properties
toggleKey=GRAVE_ACCENT
align=CENTER
modes=Native:1.0x1.0, Thin:0.2x1.0, Eye Measure:0.1x1.0, Wide Short:1.0x0.45
```

- `toggleKey` — the key that cycles modes. Accepts a **GLFW key name**
  (`GRAVE_ACCENT`, `BACKSLASH`, `RIGHT_BRACKET`, `G`, `UP`, …) or a raw numeric
  code. Case, surrounding spaces and a `GLFW_KEY_` prefix are all tolerated.
  Amethyst's on-screen buttons emit the same codes, so one setting covers both a
  physical key and a touch button.
- `align` — `CENTER` (default), `LEFT` or `RIGHT`: where the rendered strip sits
  on the real screen. `LEFT` is what GL does unaided, since its viewport origin
  is the bottom-left corner.
- `modes` — `Name:WidthFractionxHeightFraction`, as **fractions of the device's
  native surface**, 0.01–1.0.

The default is `` ` `` rather than a function key, because plenty of tablet and
compact keyboards have no F-row at all. Other keys unbound in vanilla 1.16.1 and
easy to reach: `` ` `` `\` `[` `]` `;` `'` `,` `.` `-` `=`. Avoid `/` and `T`,
which open chat.

The name table is generated directly from Amethyst's own
`Natives/glfw_keycodes.h`, so the codes match what the launcher actually
delivers — including its `DPAD_*` naming for the arrow keys, aliased to the
standard `UP` / `DOWN` / `LEFT` / `RIGHT` here.

Fractions rather than Toolscreen's absolute `game_width`/`game_height` because a
desktop monitor is a fixed known size, while the same config here has to survive
an iPhone, an iPad and an external display. Resolved values are forced even, to
match the launcher's own rounding.

It is editable **on-device**, deliberately: rebuilding needs a computer, and
tuning mode dimensions without a rebuild is the difference between a usable
spike and a useless one.

## Building

Pre-built jars come from CI — every push builds one and attaches it to the run,
so **no local build environment and no Mac are needed**. Grab it from the
[Actions tab](https://github.com/adamarmanyos-max/Toolscreen/actions/workflows/build-mod.yml):
open the latest green run and download the `toolscreen-mobile` artifact.

To build locally instead (any OS with JDK 17 — this is a Java mod, not an iOS
app):

```bash
cd spike-fabric-mod
./gradlew build
```

The jar lands in `build/libs/`. Copy it into Amethyst's `.minecraft/mods/`
alongside Fabric Loader for 1.16.1.

Gradle is pinned to **8.7** by the wrapper: Loom 1.6 calls
`org.gradle.api.problems.Problems.forNamespace`, which existed in Gradle 8.6
through 8.12 and was removed by 8.13. Newer Gradle fails at configuration time
with `NoSuchMethodError` before compiling anything.

---

## Status

**Compiles clean.** CI builds it with Fabric Loom 1.6.12 against Minecraft
1.16.1 / yarn `1.16.1+build.21` on JDK 17: `compileJava`, `jar`, `remapJar` and
`remapSourcesJar` all succeed.

That result is worth more than it looks. Loom runs the Mixin annotation
processor during `compileJava`, and it resolves `@Shadow` and `@Inject` targets
against the remapped Minecraft classes — an unresolvable name is a compile
error. A successful build therefore **confirms every yarn name the mod depends
on**:

| Name | Used by |
| --- | --- |
| `Window#getFramebufferWidth` / `getFramebufferHeight` | `WindowMixin` inject targets |
| `Window.framebufferWidth` / `framebufferHeight` | `WindowMixin` `@Shadow` fields |
| `InputUtil.isKeyPressed(long, int)` | `MinecraftClientMixin` |
| `MinecraftClient#onResolutionChanged()` | `MinecraftClientMixin` |
| `MinecraftClient#tick` | `MinecraftClientMixin` inject target |

`Mode`'s fraction/rounding/clamping logic also passes a standalone unit test
(even rounding, floor of 2, clamping, NaN fallback, blank-name rejection), and
both JSON resources parse.

### What is still unproven

Compiling is not running. **The mod has never been launched**, on iOS or
anywhere — nothing below has been observed, only reasoned about from the
launcher's source. These are the questions the spike exists to answer:

1. **Does the override survive a real resize?** The reasoning says yes: a
   genuine resize calls Minecraft's callback, which re-reads the overridden
   getters. Rotate the device and background/resume the app to confirm.
2. **Touch input alignment.** Minecraft maps cursor position through the
   dimensions it believes in, while touches land on the *real* surface. Taps are
   likely to misalign once the reported size diverges. **This is the biggest
   risk to the whole route** and the main thing to measure.
3. **Does the area outside the render get cleared?** The blit covers only the
   sub-rect; the rest of the default framebuffer may retain stale frames and
   smear. If so it needs an explicit clear.
4. **Is a thin window usable with touch controls at all?** The question that
   decides whether any of this is worth building out.

If (1) holds and (2) is fixable, Route C is viable and independently shippable
to every stock-Amethyst user. If (2) proves intractable, that is a strong
argument for Route A (fork the launcher), where input mapping is controllable.
