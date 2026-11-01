# SpeedSMP Auction plugin

Paper 1.21.11 plugin for the SpeedSMP tier auction.

## Server requirements

- Paper 1.21.11, running Java 21.
- Citizens installed before this plugin. Citizens supplies the player-model NPC and username skin used for offline roster members.

Put both `Citizens.jar` and `auction-plugin-1.0.0.jar` in `plugins/`, then restart. The first startup creates `plugins/SpeedSMPAuction/config.yml` and `data.yml`.

## Admin setup

Admin commands require `auction.admin` (OPs have it by default).

1. Register players, including offline players. `/tiers add <name> <tier>` also whitelists them. `/tier <name> set <S|A|B|C|D>` changes an existing player's tier.
2. Set up the eight S-tier captains' colors with `/assign <color> <S-tier-player>`, including while they are offline, or let an online S-tier player choose a free dye color from `/tiers`. The auction requires all eight colors and all eight `/posteam` destinations to be configured before it starts.
3. Registered players appear in chat, the player list, and above their character as `(C) Name`, using their assigned team color or the normal Minecraft name color when unassigned.
4. Stand at each auction lineup spot and save it with `/pos add 1` through `/pos add 8`. `/pos delete <number>` removes a saved spot.
5. Stand at each team destination and use `/posteam <color>`.
6. Set join locations with `/axs set all` and `/axs set S`.
7. Place the physical barrier blocks around the movement area. Non-S players are limited to a 3-block radius from their assigned/join position. When `/pvp off` is enabled, barriers inside the configured auction area are saved to `barriers.yml` and temporarily opened. Regular players see client-side barriers; S-tier players see air, and OPs can reveal one barrier at a time by looking at it. `/pvp on` restores the saved barriers.

## Auction flow

- `/bidding start` starts at A-Tiers or resumes a paused bid. `/auction start` is an equivalent admin shortcut.
- Each tier's roster is sorted by username A-Z. One player at a time is teleported to the matching saved `/pos` number, receives a three-second countdown, and has a 20-second bidding window. They can jump and look around but cannot walk from their podium. Offline players appear as protected Citizens player NPCs using their account skin.
- Only S-tier captains can place bids. While bidding is open, a numeric chat message such as `30` becomes a team-colored `I bid 30!` announcement. A-D players' chat is suppressed during that window. Bids must be above the current bid and within that team's remaining 250 auction money. Outbid bids are not charged.
- The first accepted bid adds 5 seconds. Each later accepted higher bid adds 10 seconds to that player's timer.
- `/sell` closes the current player's auction early. Otherwise, the 20-second timer closes it automatically. The winner pays the winning amount, the player is assigned the team's color, and they are sent to that team's `/posteam` location.
- `/bidding pause` freezes the current bidding timer. `/bidding stop` ends the auction and keeps completed sales and balances.
- `/bidding restart <A|B|C|D>` clears and reruns that tier. Winning prices recorded in `purchases.yml` are refunded to the teams that paid them. Later tiers' completed sales remain in place and are skipped as the auction continues. Sales from older plugin versions have no saved winning prices and cannot be refunded accurately.
- A player with no bids stays at their podium while the rest of the tier is auctioned. When that tier finishes, unsold players are assigned to eligible teams with the most remaining budget. Team members are sent to spaced spots around the team's `/posteam` location to avoid stacking.
- At the end of D-Tiers, the auction closes. Unused auction money has no season value.

## Commands

| Command | Access | Purpose |
|---|---|---|
| `/tiers` | Everyone | Opens the tier GUI; S-tier players can choose an unclaimed color there. |
| `/tiers add <name> <tier>` | Admin | Adds an online or offline player to a tier and whitelists them. |
| `/tier <name> set <tier>` | Admin | Changes a player's tier. |
| `/assign <color> <S-tier-player>` | Admin | Assigns a color to an S-tier captain, online or offline. |
| `/pos add <1-8>` | Admin | Saves a lineup location at your current position. |
| `/pos delete <1-8>` | Admin | Deletes a saved lineup location. |
| `/posteam <color>` | Admin | Saves the team's sale destination at your current position. |
| `/axs set all` | Admin | Saves the normal join location. |
| `/axs set S` | Admin | Saves the S-tier join location. |
| `/bidding start` | Admin | Starts the auction. |
| `/bidding pause` | Admin | Pauses the current bidding timer. Use `/bidding start` to resume. |
| `/bidding stop` | Admin | Stops the auction without undoing completed sales. |
| `/bidding restart <A|B|C|D>` | Admin | Refunds recorded winning bids for the selected tier, clears its assignments, and auctions that tier again. |
| `/sell` | Admin | Ends the current bidding window and sells the current player. |
| `/auction stop` | Admin | Stops the auction. |
| `/auction npcs clear` | Admin | Removes saved auction stand-in NPCs while keeping the roster. |
| `/pvp off` / `/pvp on` | Admin | Applies/removes the server-wide protected mode. |

`/pvp off` affects current players and future joiners. It disables player-versus-player damage, applies invisibility and Saturation 255, prevents block breaking/placing and block/entity/container interactions, and uses Adventure mode for non-S players. S-tier players and OPs keep their saved game mode (they are not put in spectator). Arena barrier blocks are temporarily removed server-side so S-tier players and OPs can pass; regular players receive a client-side barrier view. `/pvp on` restores the barriers, saved game modes, and prior potion effects.

## Offline players

Citizens creates a protected player NPC with the roster player's skin. When the real player joins, their NPC is removed and the real player is sent to their team location if sold, otherwise to their S-tier or normal join location. An offline S-tier player cannot submit bids while offline; the NPC is a stand-in, not an autonomous bidder.

## Build

Import this Maven project into IntelliJ IDEA or run `mvn package` with JDK 21. The plugin JAR is written to `target/auction-plugin-1.0.0.jar`.

The repository's GitHub Actions workflow builds the JAR on pushes and manual runs. The latest compiled JAR is also included in this folder as `auction-plugin-1.0.0.jar`. To rebuild it, download the `speedsmp-auction-plugin` artifact from a completed **Build Auction Plugin** run.
