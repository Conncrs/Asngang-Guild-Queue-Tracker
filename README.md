# Guild Queue Tracker (GameRelay)

A client-side Forge 1.8.9 mod that relays Mega Walls queue info to guild chat.

## Features

- **Queue info on request.** When someone types `queue` or `!queue` in guild chat, the mod replies with the current map, player count, and start timer, read from the lobby scoreboard. Example:
  `Solace 14/100 - starting in 03:52 if 16 more players join`
- **Countdown announcements.** Posts the "game is starting" message to guild chat at 30 seconds and 10 seconds.
- **No spam.** If several people have the mod, each one waits a short random delay and cancels if someone else has already sent the same message, so only one person replies.
- **Only answers from a queue.** If you're not in a queue lobby, it stays quiet.

## Install

1. Install Minecraft 1.8.9 with [Forge 1.8.9](https://files.minecraftforge.net/) (11.15.1.2318).
2. Download `GameRelay.jar` from the [Releases](../../releases) page.
3. Put it in your `.minecraft/mods` folder and launch the Forge 1.8.9 profile.

## Commands

| Command | What it does |
|---|---|
| `/gamerelay` | Turns the mod on or off |
