# Tuxy Motion Blur — Minecraft Fabric 1.21.11

A tiny client-side, always-on temporal motion blur mod aimed at the classic Badlion-style PvP look.

## Behavior

- Always enabled while a world is rendered.
- No config screen, command, or keybind.
- HUD, crosshair, hotbar and text are rendered after the blur pass and stay sharp.
- Uses a persistent previous-frame buffer instead of a Gaussian screen blur.
- Frame-rate compensated: the trail is tuned around a 0.76 previous-frame blend at 60 FPS and adjusts per frame so the perceived strength stays similar at higher FPS.
- Resets frame history when entering a world or after a long pause, avoiding stale-frame flashes.

## Requirements

- Minecraft Java Edition 1.21.11
- Fabric Loader 0.18.1 or newer
- Java 21
- Fabric API is not required

## Build

Run `gradle clean build` with Gradle 9.4.1. The JAR is created under `build/libs`.

## Reference

The visual target requested for this build was the motion blur shown around 2:57 in:
https://www.youtube.com/watch?v=G_-FqCxdnAE&t=177s

## Credits

The modern Minecraft 1.21.11 post-processing hook and persistent-target approach were informed by LunaBlur by Kaan (MIT licensed), with this project simplified to a permanently enabled effect and modified to use frame-rate-compensated temporal blending.
