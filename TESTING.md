# Testing the spike on iOS

How to get the mod onto an iPad running Amethyst and find out whether the idea
works. Everything here is doable **on the device alone** — no computer, no Mac.

Paths below come from the launcher's source: the game directory is
`POJAV_HOME/instances/<game_directory>/<profile gameDir>`
(`Natives/JavaLauncher.m:177`) and `general.game_directory` defaults to
`default` (`Natives/PLPreferences.m:75`). Amethyst sets `UIFileSharingEnabled`,
`LSSupportsOpeningDocumentsInPlace` and `UISupportsDocumentBrowser`
(`Natives/Info.plist`), so its folder is visible in the Files app.

---

## 1. Baseline first — Fabric 1.16.1 with no mod

Do this before touching the jar. If it fails, nothing after it means anything.

1. Open Amethyst → **Profiles**.
2. Use the add menu → **Fabric/Quilt** (the launcher has a built-in installer —
   `LauncherProfilesViewController.m:63`; no need to sideload one).
3. Choose Minecraft **1.16.1**, install, then launch it and load into a world.

Confirm it runs at a playable framerate before continuing.

**Also check now:** open the on-screen control editor and see whether a button
can be bound to a raw key — specifically `` ` `` (grave accent). The mode toggle
depends on it if you are playing on touch. If you use a physical keyboard, skip
this.

## 2. Get the jar

On the iPad:

1. Open the [Build mod workflow](https://github.com/adamarmanyos-max/Toolscreen/actions/workflows/build-mod.yml)
   in Safari and sign in to GitHub.
2. Open the most recent green run → **Artifacts** → `toolscreen-mobile`.
   It downloads as a `.zip`.
3. In **Files**, tap the zip to expand it. Inside is
   `toolscreen-mobile-0.1.0-spike.jar` (plus a sources jar, which you do not
   need).

## 3. Install it

In **Files** → *On My iPad* → **Amethyst**:

```
Amethyst/
└── instances/
    └── default/
        ├── mods/     ← put the jar here (create the folder if absent)
        ├── config/   ← toolscreen-mobile.properties appears here after launch
        └── logs/
            └── latest.log
```

Copy the jar into `instances/default/mods/`. Use the **non**-sources jar.

If you set a different game directory in Amethyst's settings, substitute that
name for `default`.

## 4. Run it

Launch the 1.16.1 Fabric profile. Two checks before anything else:

- The mod list / log should show `toolscreen-mobile` loading.
- `instances/default/logs/latest.log` should contain **two** lines:

  ```
  [toolscreen-mobile] ready: 4 mode(s), toggle key GRAVE_ACCENT (96)
  [toolscreen-mobile] centring active (align=CENTER)
  ```

  They mean different things. The first says the mod loaded and read its
  config. The second says the centring redirect actually attached — it only
  appears once a frame has been drawn through it. If the first appears
  without the second, centring degraded silently and the strip will render
  against the left edge; that is worth reporting, because the game will not
  crash to tell you.

If the game crashes on launch, stop here and keep `latest.log` — a mixin failure
names the exact injection point that went wrong, which is enough to fix it.

Then press `` ` `` (or the on-screen button bound to it) to cycle:

```
Native → Thin → Eye Measure → Wide Short → Native → ...
```

Each press logs `[toolscreen-mobile] mode -> <name>`.

## 5. What to actually look for

The point of the spike is these four answers. Please note them as you go —
they decide what gets built next.

| # | Question | How to check |
| --- | --- | --- |
| 1 | **Does the shape change at all?** | Press `` ` ``. The game should render into a narrow strip instead of the full screen. |
| 2 | **Do taps still land where you aim?** | In Thin mode, tap a hotbar slot, open inventory, click a specific item. Does the hit point drift from your finger? |
| 3 | **Does the rest of the screen smear?** | Look at the area *outside* the strip. Black is fine; a frozen or repeating copy of the game is the bug. |
| 4 | **Does it survive a resize?** | While in Thin mode: rotate the device, and background the app then reopen it. Does it stay thin? |

**Question 2 is the one that matters most.** Minecraft maps pointer position
through the dimensions it believes in, while your finger lands on the real
surface. If taps drift badly and cannot be corrected, a Java-side mod is the
wrong approach and the project should move to Route A (forking the launcher),
where input mapping is controllable.

## 6. Tuning without rebuilding

After first launch, edit `instances/default/config/toolscreen-mobile.properties`
directly in Files:

```properties
toggleKey=GRAVE_ACCENT
align=CENTER
modes=Native:1.0x1.0, Thin:0.14x1.0, Eye Measure:0.08x1.0, Wide Short:1.0x0.25
```

- A number of `1.0` or less is a **fraction of your screen**, so `0.14` is 14%
  of the width. Anything larger is an **absolute pixel count**, so `Thin:280x1024`
  is exactly 280×1024 — the form Toolscreen uses, so its presets can be copied
  straight across.
- If a mode is too narrow or too wide, change it and relaunch — no rebuild.
- `toggleKey` takes a name (`GRAVE_ACCENT`, `BACKSLASH`, `RIGHT_BRACKET`, `G`)
  or a raw number.
- `align` takes `CENTER` (default), `LEFT` or `RIGHT`. `LEFT` is where GL puts
  the strip unaided, since its viewport origin is the bottom-left corner.

## 7. If something goes wrong

Send back `instances/default/logs/latest.log`. It is the single most useful
thing — it contains the mod's own log lines, and any mixin or crash detail.

Useful distinctions:

- **Game will not start at all** → likely a mixin injection failure; the log
  names the method. `Scanned 0 target(s)` means the selector matched nothing,
  which is a wrong target rather than a wrong idea. Note that the centring
  mixin can no longer cause this: it is `require = 0`, so it degrades to
  left-aligned instead of aborting startup.
- **Game starts, no `[toolscreen-mobile]` line** → the jar is not being loaded;
  check it is in `mods/` and that the profile really is Fabric 1.16.1.
- **Loads, but `` ` `` does nothing** → key not reaching the game. Try setting
  `toggleKey` to a letter such as `G` to confirm the polling works at all.
- **Shape changes but everything is unusable** → that is a *result*, not a
  failure. Note what specifically broke.
