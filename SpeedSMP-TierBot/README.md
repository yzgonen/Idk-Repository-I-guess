# SpeedSMP TierBot

Discord slash-command tier list bot for S/A/B/C/D/F with Minecraft pixel heads.

## Setup
1. Install Node.js 20+ and run `npm install` in this folder.
2. Copy `.env.example` to `.env` and fill `DISCORD_TOKEN` (keep private) and your Discord server `GUILD_ID`.
3. Run `npm start`. Keep the process running or deploy to a Node.js hosting service.
4. In Discord use `/tierlist create`, `/tierlist add`, `/tierlist move`, `/tierlist remove`, `/tierlist show`.

Commands require Manage Server permission. Minecraft heads use Minotar, not NameMC. Username syntax is validated, but account existence is not checked. The PNG displays up to nine heads per tier. Data is stored in local `tierlists.json`, so persistent hosting storage is needed.

GitHub Actions tests syntax only; GitHub does not keep the bot online. Do not commit your bot token.
