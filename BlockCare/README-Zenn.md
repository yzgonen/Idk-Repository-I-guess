# Zenn PC Optimizer

A Windows x64 PC optimizer adapted to the supplied HP Pavilion Gaming Desktop specs: Intel Core i5-8400, GeForce GTX 1060 6 GB, and 16 GB RAM.

## Interface

Zenn uses a dark, green-accented module dashboard with a custom blue lightning mark and 12 sections: Start, Driver, Bloatware, Telemetry, Latency, Network, Overclocking, Games, Miscellaneous, Cleanup, BIOS, and Experimental.

## Build

The project is a .NET 8 WinForms app. GitHub Actions publishes a self-contained Windows x64 executable named `Zenn.exe` from the `zenn-swills-style` branch.

## Behavior

The tune-up can enable Windows Game Mode and the High Performance power plan, saving the previous values for Undo. App Focus changes CPU priority only for selected running Chrome, Discord, and WhatsApp processes while a supported game is active; Windows restores their previous priorities when the game closes or Zenn exits. This does not cap RAM use.

Driver, privacy, startup, storage, network, and BIOS sections open Windows tools or report information. Zenn does not automatically remove drivers, disable Windows services, flash BIOS, or clear user files.
