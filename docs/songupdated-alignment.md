# Aligning Android port to upstream `songUpdated` flow

**Status:** direction C + C-extra-1 implemented locally. `gradle :core:compileJava` and `gradle :android:assembleDebug` both pass (exit 0). **Not pushed** — pending user review.
**Scope:** direction C plus the F2 NPE null-guard (C-extra-1) so the reported bug is actually fixed.

## 1. Reported bug

With config option **Scan Songs On Launch** (`Config.updatesong`) turned **off**, pressing **F2** in the music select screen no longer triggers an update. Turning the toggle back on restores the behaviour. The user wants manual update to work regardless of the auto-refresh setting.

> "现在项目仍然存在关闭默认自动刷新之后无法手动update song的情况"

## 2. Upstream behaviour (the reference)

`endlessdream-upstream-src/bms/player/beatoraja/select/MusicSelector.java:116` (constructor):

```java
public MusicSelector(MainController main, boolean songUpdated) { ... }

if (!songUpdated && main.getPlayerResource().getConfig().isUpdatesong()) {
    main.updateSong(null);
}
```

`endlessdream-upstream-src/bms/player/beatoraja/launcher/PlayConfigurationView.java:303`:

```java
private boolean songUpdated = false;
```

…set to `true` at line 916 after the launcher's **loadBMS** button finishes a pre-MusicSelector scan:

```java
songUpdated = true;
```

This flag is then propagated `MainLoader.play(...) → MainController(...) → MusicSelector(...)` so that MusicSelector's constructor knows "the launcher already scanned, so I should NOT auto-scan again, regardless of `isUpdatesong()`".

`endlessdream-upstream-src/bms/player/beatoraja/skin/property/EventFactory.java:294-308` (F2 handler):

```java
update_folder(211, state -> {
    if (state instanceof MusicSelector selector) {
        Bar selected = selector.getBarManager().getSelected();
        if (selected instanceof FolderBar) {
            selector.main.updateSong(((FolderBar) selected).getFolderData().getPath());
        } else if (selected instanceof TableBar) {
            selector.main.updateTable((TableBar) selected);
        } else if (selected instanceof SongBar) {
            ...
        }
    }
}),
```

F2 only acts on a selected **FolderBar / TableBar / SongBar**. It does **not** consult `isUpdatesong()`.

## 3. Current port deviations

| # | Port code | Upstream equivalent | Behaviour diff |
|---|-----------|---------------------|----------------|
| 1 | `AndroidLauncher.java:361` passes `new BeatorajaGame(null, null, null, BMSPlayerMode.AUTOPLAY, true)` — the trailing `true` is **`useAudio`** (per `BeatorajaGame` ctor at line 33) | — | The 5th arg flows all the way to `MainController.java:126` ctor which interprets it as **`songUpdated`** |
| 2 | `MainController.java:130` `this.songUpdated = songUpdated;` writes **whatever the previous layer passed** | Same | Field is set, but contains `useAudio=true` (always true today) — the launcher-side scan flag and the audio flag are sharing one slot |
| 3 | `MusicSelector.java:116` ctor takes `songUpdated` but **the body never reads it** (`grep songUpdated` shows only the parameter declaration) | `MusicSelector.java:136-138` reads `songUpdated` | Port ignores the pre-scan hint |
| 4 | `MusicSelector.java:217-250` (create) has an Android-only block that runs the scan based purely on `isUpdatesong()`, with two `Logger.getGlobal().info(...)` calls and a `new Thread(...)` wrapper | Upstream does the scan check **inside the constructor** and lets `MainController.updateSong(null)` start the thread | The startup decision is hidden behind platform-specific code instead of the canonical pattern |
| 5 | `MusicSelector.create()` always runs `manager.updateBar()` itself, even when `updatesong=false` (line 212-214) | Upstream also calls `manager.updateBar()` in `create()` (`endlessdream...MusicSelector.java:181`) | None — just noting |

Commit `3e06fb0e feat(android): add scan-on-launch toggle and first-launch onboarding` introduced #4 (and removed a `dbEmpty` fallback). The intent was "respect the user's switch", but it also created today's bug: when the DB ends up empty *and* the switch is off *and* the user then presses F2, F2 silently does nothing (see §5).

## 4. Direction C — what "true alignment" changes

The user picked **direction C** over the two earlier alternatives (A = restore `dbEmpty` fallback, B = fall back to `updateSong(null)` inside the F2 event handler). Direction C means plumbing the existing `songUpdated` flag all the way through, not adding new code paths.

### 4.1 `AndroidLauncher` — pass `songUpdated` as its own argument

Today the launcher has no pre-scan flow, so `songUpdated=false` is the correct call-site value. No behavioural change to default users; this only unblocks future hooks (e.g. a future "scan first" launcher button could set it to `true`).

`android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java:361`:

```java
initialize(new BeatorajaGame(null, null, null, BMSPlayerMode.AUTOPLAY, false), config);
```

### 4.2 `BeatorajaGame` — replace `useAudio` with `songUpdated`

`useAudio` was dead code (no reader) and shared a slot with `songUpdated`, corrupting the launcher's pre-scan signal. Removing `useAudio` makes the constructor match upstream's `(File, Config, PlayerConfig, BMSPlayerMode, boolean songUpdated)` shape.

`core/src/main/java/com/starxh/beatoraja/BeatorajaGame.java:33-48`:

```java
public BeatorajaGame(File rootPath, Config bmsConfig, PlayerConfig playerConfig,
                     BMSPlayerMode mode, boolean songUpdated) {
    this.rootPath = rootPath;
    this.bmsConfig = bmsConfig;
    this.playerConfig = playerConfig;
    this.mode = mode;
    this.songUpdated = songUpdated;
}

@Override
public void create() {
    controller = new MainController(rootPath, bmsConfig, playerConfig, mode, songUpdated);
    ...
}
```

### 4.3 `MainController` — already correct

`MainController.java:126` already declares `MainController(File f, Config config, PlayerConfig player, BMSPlayerMode auto, boolean songUpdated)` and stores into `this.songUpdated` (line 130). It was the *value* that was wrong because of the upstream chain — now it receives the correct value from `BeatorajaGame`. **No code change needed in `MainController.java`.**

### 4.4 `MusicSelector` — honour `songUpdated` in `create()`

Two options:

- **(4.4-a) Move the scan trigger into the constructor** (literally mirror upstream). Risk: synchronous `main.updateSong(null)` could block the render thread on Android before `create()` is called.
- **(4.4-b) Keep it in `create()` but use upstream's exact predicate**: `!songUpdated && isUpdatesong()`. Drop the Android-async `new Thread(...)` wrapper if `MainController.updateSong(...)` already spawns a thread (it does — `MainController.java:1234-1239`).

Plan: go with **4.4-b** to avoid regressing the GL-thread-safety work that was added in commit `3e06fb0e`. The change is small: replace lines 217-250 of `MusicSelector.java` with the upstream predicate, keep only the **async dispatch wrapper** (so we don't block the GL thread on a potentially huge scan), and remove the `Logger` chatter that no longer helps.

Sketch (final form will be in the diff):

```java
if (!songUpdated && config.isUpdatesong()) {
    Gdx.app.postRunnable(() -> main.updateSong(null));
}
```

This mirrors upstream behaviour (`!songUpdated && isUpdatesong()`) and **does not** add Android-only divergence: `MainController.updateSong` already spawns the worker thread (`MainController.java:1267+`), so a synchronous call there is safe.

### 4.5 Constructor parameter (existing)

`MusicSelector.java:116` already takes `(MainController main, boolean songUpdated)` — keep as-is, just route `songUpdated` from `MainController.java:546` (which already passes it). No change needed there.

## 5. What this does NOT fix

Direction C alone does **not** repair the reported F2 symptom. The reason:

With `updatesong=false` and an empty DB, the music-select screen still shows the root FolderBar as the default selection (`selectedindex=0` default in `BarManager.java:67` and the root bar is the first entry added at line 291). That root FolderBar is constructed with `folder=null` (`new FolderBar(select, null, "e2977170")` at `BarManager.java:291`).

When the user presses F2 with this bar selected, the event handler hits:

```java
selector.main.updateSong(((FolderBar) selected).getFolderData().getPath());
```

`getFolderData()` returns the field `folder`, which is **null** for the root bar. The chained `.getPath()` therefore throws `NullPointerException`. The exception is swallowed by the dispatcher (no logger inside the lambda), so the user observes "F2 did nothing".

This is **also a bug in upstream** (identical code at `EventFactory.java:294-308`). Upstream avoids it in practice because the launcher's `loadAllBMS` / `loadDiffBMS` / `loadBMSPath` buttons populate the DB before MusicSelector opens, so there's no scenario where F2 lands on the empty root bar.

The Android port **doesn't** have an equivalent pre-launch screen. There are two ways to close that gap, both raised here:

- **C-extra-1 (applied):** Add a null guard in the F2 handler so the root FolderBar case falls back to a full scan:
  ```java
  if (selected instanceof FolderBar) {
      FolderData fd = ((FolderBar) selected).getFolderData();
      selector.main.updateSong(fd != null ? fd.getPath() : null);
  }
  ```
  Treating null-folder as "scan everything" mirrors `main.updateSong(null)` semantics — matches upstream's intent (a full scan is the right behaviour when the selected folder is the root).

- **C-extra-2 (not applied):** Add a separate "manual scan" entry point (e.g. a settings-screen button) that always scans regardless of `updatesong`. More invasive, deferred.

With C-extra-1 the bug is fully fixed: F2 on the empty root FolderBar now triggers a full scan via `main.updateSong(null)` instead of dying silently. Behaviour summary after the combined C + C-extra-1:

| State at F2 press | Behaviour |
|-------------------|-----------|
| Populated FolderBar selected | Update that subtree (unchanged) |
| Empty root FolderBar (`folder==null`) selected | Full scan via `updateSong(null)` (was NPE before fix) |
| SongBar selected | Update parent of that song (unchanged) |
| TableBar selected | Update that table (unchanged) |
| Nothing / other bar type | No-op (unchanged) |

## 6. Build/test checklist (pre-push)

- [x] No compile errors: `gradle :core:compileJava` clean.
- [x] No compile errors in Android module: `gradle :android:compileDebugJavaWithJavac` clean.
- [x] Full APK builds: `gradle :android:assembleDebug` exit 0.
- [x] Sanity diff: `git diff --stat` shows only 3 files (see §7) plus no unintended drift.
- [ ] Manual smoke on emulator (deferred to user):
  - [ ] With `updatesong=true`, app scans on launch (auto).
  - [ ] With `updatesong=false`, app does **not** scan on launch but still shows the last-known bars.
  - [ ] F2 on a populated bar still updates that subtree (no regression).
  - [ ] F2 on the empty root bar — **direction C alone**: still does nothing (NPE, see §5); **C-extra-1 added**: triggers full scan.

## 7. Files touched by direction C + C-extra-1

1. `core/src/main/java/com/starxh/beatoraja/BeatorajaGame.java` — ctor + create.
2. `core/src/main/java/bms/player/beatoraja/MainController.java` — no code change, just receives the correct value now (was already `MainController(File, Config, PlayerConfig, BMSPlayerMode, boolean songUpdated)`).
3. `core/src/main/java/bms/player/beatoraja/select/MusicSelector.java` — startup scan predicate + remove Android-only thread wrapper, `songUpdated` now an instance field.
4. `android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java:361` — call-site update.
5. `core/src/main/java/bms/player/beatoraja/skin/property/EventFactory.java:285-289` — null-guard on root FolderBar's `getFolderData()` (C-extra-1).

`docs/songupdated-alignment.md` — this file (new).
