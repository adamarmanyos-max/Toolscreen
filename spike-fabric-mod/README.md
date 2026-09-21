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
toggleKey=295
modes=Native:1.0x1.0, Thin:0.2x1.0, Eye Measure:0.1x1.0, Wide Short:1.0x0.45
```

- `toggleKey` — GLFW key code that cycles modes (295 = F6). Bind an Amethyst
  on-screen button to the same code.
- `modes` — `Name:WidthFractionxHeightFraction`, as **fractions of the device's
  native surface**, 0.01–1.0.

Fractions rather than Toolscreen's absolute `game_width`/`game_height` because a
desktop monitor is a fixed known size, while the same config here has to survive
an iPhone, an iPad and an external display. Resolved values are forced even, to
match the launcher's own rounding.

It is editable **on-device**, deliberately: rebuilding needs a computer, and
tuning mode dimensions without a rebuild is the difference between a usable
spike and a useless one.

## Building

Requires network access to `maven.fabricmc.net`, `piston-meta.mojang.com` and
`libraries.minecraft.net`.

```bash
cd spike-fabric-mod
gradle build        # or ./gradlew build
```

The jar lands in `build/libs/`. Copy it to Amethyst's `.minecraft/mods/`
alongside Fabric Loader for 1.16.1.

---

## Status — read this before testing

**This has never been compiled or run.** It was written in an environment whose
egress proxy denies Fabric's and Mojang's Maven hosts:

```
maven.fabricmc.net:443     — connect_rejected (organization policy)
piston-meta.mojang.com:443 — connect_rejected
libraries.minecraft.net:443 — connect_rejected
```

so Loom could not fetch Minecraft or the yarn mappings. What *was* verified:
`Mode`'s fraction/rounding/clamping logic passes a standalone unit test (even
rounding, floor of 2, clamping, NaN fallback, blank-name rejection), and both
JSON resources parse. Nothing that touches Minecraft has been checked by a
compiler.

### Expect to fix on first build

The yarn names below are from memory and are the most likely breakages. The
compiler will tell you immediately, and each is a rename, not a redesign:

| Used | Where |
| --- | --- |
| `Window#getFramebufferWidth` / `getFramebufferHeight` | `WindowMixin` inject targets |
| `Window.framebufferWidth` / `framebufferHeight` fields | `WindowMixin` `@Shadow` |
| `InputUtil.isKeyPressed(long, int)` | `MinecraftClientMixin` |
| `MinecraftClient#onResolutionChanged()` | `MinecraftClientMixin` |
| `MinecraftClient#tick` | `MinecraftClientMixin` inject target |

`fabric-loom 1.6-SNAPSHOT` against 1.16.1 with a Java 17 toolchain may also need
a version nudge.

### Open questions the spike is meant to answer

1. **Does the override survive a real resize?** The code reasons it should, from
   the launcher's event path. Rotate the device and resume the app to confirm.
2. **Touch input alignment.** Minecraft maps cursor position through the window
   dimensions it believes in, while touches land on the *real* surface. Touch
   coordinates are likely to misalign once the reported size diverges — this is
   the single biggest risk to the whole route, and the main thing to measure.
3. **Does the area outside the render get cleared?** The blit only covers the
   sub-rect; the rest of the default framebuffer may retain stale frames and
   smear. If so, it needs an explicit clear.
4. **Is a thin window actually usable with touch controls at all?** The question
   that decides whether any of this is worth building out.

If (1) holds and (2) is fixable, Route C is viable and independently shippable
to every stock-Amethyst user. If (2) proves intractable, that is a strong
argument for Route A (fork the launcher), where input mapping is controllable.
