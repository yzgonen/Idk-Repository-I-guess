# TAS Workbench — source project

A clean BakkesMod settings panel for the TAS workflow. The source project provides:

- A simple **Shot** tab that calls the TAS plugin's documented `tas_start`, `tas_stop`, and `tas_stopandupdate` commands.
- A **Binds** tab with keyboard and mouse key names, including `Mouse4` and `Mouse5`.
- A **Mutators** tab that builds a Soccar private match using BakkesMod's `CustomMatchSettings` and Rocket League mutator tags.
- An offline **game speed** bind that cycles `sv_soccar_gamespeed` through 0.5x, 1x, and 2x.

## What this is

This is new C++ source for a companion plugin named `TASWorkbench`. It gives the TAS workflow a clearer interface and adds real custom-match mutator setup. The original TAS plugin remains the recording/playback engine because the file supplied in this workspace is a compiled DLL; this workbench does not reverse-engineer or patch its internals.

The panel intentionally does not show controls for start-point editing, undo history, top-bin aim, video recording, or TAS file management. The original plugin exposes no public API for those actions, and showing buttons that cannot perform them would be misleading. They need a separate recorder implementation or access to the original source and a game-side test pass.

## Build on Windows

1. Install Visual Studio 2022 with **Desktop development with C++**.
2. Locate the BakkesMod SDK folder. A typical install has it under `%appdata%\bakkesmod\bakkesmod\bakkesmodsdk` and it contains `include` and `lib` folders.
3. Open **x64 Native Tools Command Prompt for VS 2022**.
4. From this folder, run:

   ```bat
   cmake -S . -B build -A x64 -DBAKKESMOD_SDK="%appdata%\bakkesmod\bakkesmod\bakkesmodsdk"
   cmake --build build --config Release
   ```

5. Copy `build\Release\TASWorkbench.dll` into BakkesMod's `plugins` folder.
6. In Rocket League, open F6 and run `plugin load TASWorkbench`.

If your SDK lives elsewhere, replace the path in step 4. This source has not been compiled in this Linux workspace because it does not contain the Windows SDK libraries or MSVC toolchain.

## Use

- Load the original TAS plugin and `TASWorkbench`.
- Open the BakkesMod F2 menu and select **TAS Workbench**.
- Use **Shot** for replay/stop/update and the speed control.
- Use **Binds** to type a key name and press **Apply**. The plugin writes the bind and runs `writeconfig`.
- Use **Mutators** to choose actual Soccer custom-match mutators, select a region and map package, then press **Create private match**. This starts a private match; it does not change mutators in an already-running freeplay session.

The mutator tag string is built from one selected option per mutator group and passed through the SDK's `CustomMatchSettings.GameTags` field. Some tag names depend on Rocket League's installed build; if a tag is rejected by the game, the in-game match settings/log are the source of truth.

## Known boundaries

- TAS controls call the original plugin's console commands, so that plugin must be loaded. Its commands are documented by the TAS plugin author.
- Game speed is limited to offline/freeplay/custom training by BakkesMod permissions.
- Ball max speed uses Rocket League's **Default / Slow / Fast** mutator options. It is not a fabricated numeric “2x” control.
- The “top-bin aim” assist and true TAS undo/start-point history are not implemented in this companion build.
