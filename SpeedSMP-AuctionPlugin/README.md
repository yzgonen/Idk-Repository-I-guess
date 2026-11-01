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
3. Stand at each auction lineup spot and save it with `/pos add 1` through `/pos add 8`. `/pos delete <number>` removes a saved spot.
4. Stand at each team destination and use `/posteam <color>`.
5. Set join locations with `/axs set all` and `/axs set S`.
6. Place the physical barrier blocks around the movement area. Non-S players are limited to a 3-block radius from their assigned/join position. S-tier players and OPs use spectator mode while `/pvp off` is active so they can pass through physical barriers; S-tier clients are sent air block updates for nearby barriers.

## Auction flow

- `/bidding start` starts at A-Tiers. `/auction start` is an equivalent admin shortcut.
- Each tier's roster is sorted by username A-Z. One player at a time is teleported to the matching saved `/pos` number, receives a three-second countdown, and has a 30-second bidding window. Offline players appear as protected Citizens player NPCs using their account skin.
- Only S-tier captains can place bids. While bidding is open, a numeric chat message such as `30` becomes a team-colored `I bid 30!` announcement. A-D players' chat is suppressed during that window. Bids must be above the current bid and within that team's remaining 250 auction money. Outbid bids are not charged.
- `/sell` closes the current player's auction early. Otherwise, the 30-second timer closes it automatically. The winner pays the winning amount, the player is assigned the team's color, and they are sent to that team's `/posteam` location.
- A player with no bids is assigned to an eligible team with the most remaining budget. This keeps each team to one player per tier where possible.
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
| `/sell` | Admin | Ends the current bidding window and sells the current player. |
| `/auction stop` | Admin | Stops the auction. |
| `/pvp off` / `/pvp on` | Admin | Applies/removes the server-wide protected mode. |

`/pvp off` affects current players and future joiners. It disables player-versus-player damage, applies invisibility and Saturation 255, prevents block breaking/placing and block/entity/container interactions, and uses Adventure mode for non-S players. `/pvp on` restores saved game modes and removes those effects.

## Offline players

Citizens creates a protected player NPC with the roster player's skin. When the real player joins, their NPC is removed and the real player is sent to their team location if sold, otherwise to their S-tier or normal join location. An offline S-tier player cannot submit bids while offline; the NPC is a stand-in, not an autonomous bidder.

## Build

Import this Maven project into IntelliJ IDEA or run `mvn package` with JDK 21. The plugin JAR is written to `target/auction-plugin-1.0.0.jar`.

The repository's GitHub Actions workflow builds the JAR on pushes and manual runs. Download the `speedsmp-auction-plugin` artifact from the completed **Build Auction Plugin** run, then unzip it to get the JAR.
