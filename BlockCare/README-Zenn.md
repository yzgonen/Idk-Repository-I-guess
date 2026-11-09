# Zenn PC Optimizer

A Windows x64 PC optimizer adapted to the supplied HP Pavilion Gaming Desktop specs: Intel Core i5-8400, GeForce GTX 1060 6 GB, and 16 GB RAM.

## Interface

Zenn uses a dark, green-accented module dashboard with a custom blue lightning mark, animated game cards, and 12 sections: Start, Driver, Bloatware, Telemetry, Latency, Network, Overclocking, Games, Miscellaneous, Cleanup, BIOS, and Experimental. Fortnite, Minecraft, and Rocket League appear as clickable profiles on the start screen.

## Install and update

GitHub Actions builds `ZennSetup.exe`, a per-user Windows installer. Its default location is `%LOCALAPPDATA%\Programs\Zenn` (on a typical PC, under `C:\Users\<you>\AppData\Local\Programs\Zenn`). The setup wizard creates Start menu and desktop shortcuts. It does not need administrator access.

Zenn checks the GitHub release for updates at startup. When a newer version is available, choose **UPDATE AVAILABLE** in the top bar; Zenn downloads and opens the setup wizard, then closes so the installer can replace the app. Your tune-up backup data and selected game paths remain local and are preserved during updates.

## Build

The project is a .NET 8 WinForms app. GitHub Actions publishes a self-contained Windows x64 installer from the `zenn-swills-style` branch.

## Behavior

The tune-up can enable Windows Game Mode, the High Performance power plan, and a Windows per-app High performance GPU preference for the selected game executable, saving the previous values for Undo. App Focus changes CPU priority only for selected running Chrome, Discord, and WhatsApp processes while a supported game is active; Windows restores their previous priorities when the game closes or Zenn exits. This does not cap RAM use. Game launch paths are detected for common installs or can be selected manually.

Driver, privacy, startup, storage, network, and BIOS sections open Windows tools or report information. Zenn does not automatically remove drivers, disable Windows services, flash BIOS, or clear user files.
