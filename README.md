# 🪙 Coiny

**Earn your say in what your group plays next.**

Coiny is a Discord bot for gaming groups. It turns the weekly "so… what are we playing this week?"
debate into a fair system: you earn coins by actually showing up and playing with the group, and you
spend them to influence what everyone plays next. The people who participate are the ones who get a say.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-brightgreen)
![JDA](https://img.shields.io/badge/JDA-5-5865F2)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue)
![Docker](https://img.shields.io/badge/Docker-ready-2496ED)

---

## The idea

Every group has the same argument: *what are we playing this week?* Usually the loudest voice — or
whoever shouts first — wins. Coiny replaces that with a small, self-running economy:

- 🎮 **Show up and play** the group's current game → you earn **coins**.
- ⬆️ **Spend coins** to put a new game forward, or to push your pick further up the line.
- ⚖️ **Everything proposed eventually gets played.** Coins buy you a better *spot in the queue* —
  never the power to keep someone else's game out.
- 🗓️ **Every week, Coiny rotates** to the next game in line, automatically.
- 🫙 **Bored early?** The people who've been playing the current game can chip into a shared
  **"skip jar."** Once enough of them pay in, the game retires early and the next one begins.

## How a week feels

1. **Start of the week** — Coiny promotes the top game in the queue to *this week's game*
   (say, *Hollow Knight*).
2. **You play along** in the group's voice channel. About one coin lands in your balance for every
   hour you play.
3. **You want *Hades* next.** While you're playing it, you run `/queue-propose` — Coiny reads what
   you're currently playing straight from your Discord status and adds it to the queue for a coin.
4. **Someone's ahead of you in line,** so you spend one more coin with `/queue-bump` to jump them.
5. **Friends check `/queue-view`** and tap **Upvote** on the games they're hyped for.
6. **Midweek — the group's done with *Hollow Knight*.** A few of you who've been playing it run
   `/skip contribute`. That tips the skip jar over its threshold: *Hollow Knight* retires, and
   *Hades* — next in line — becomes the new current game.

> Every number here — the earn rate, the costs, the coin cap, the skip threshold — is adjustable
> per server, so each community can tune Coiny to its own vibe.

## What you can do

### Members

| Command | What it does |
| --- | --- |
| `/balance` | See your coins and recent history in this server |
| `/queue-view` | See the current game and what's next in line (with cover art) |
| `/queue-propose` | Add the game you're playing right now to the queue |
| `/queue-bump` | Spend a coin to move your game up one spot |
| `/queue-withdraw` | Pull your game back out of the queue and get your coins back |
| `/skip contribute` | Pay a coin into the skip jar to vote to move on early |
| `/skip status` | See how full the skip jar is, and how many more votes it needs |

…plus **Upvote** buttons right on the queue view.

### Server admins & moderators

| Command | What it does |
| --- | --- |
| `/coins-config` | Set the moderator role and the most coins a member can hold |
| `/coins-adjust` | Grant or deduct a member's coins (with a reason kept on the record) |
| `/queue-config` | Set the propose / bump costs and the weekly announcement channel |
| `/participation-config` | Pick which voice channels count, set the earn rate, and toggle the free first proposal |
| `/skip-config` | Tune the skip threshold, how long a game must run before it can be skipped, and who may vote |

## What keeps it fair

- **Coins are earned, not handed out.** By default it's one coin per hour spent playing the week's
  game in a designated voice channel.
- **No hoarding.** Balances are capped (each server picks the limit), so coins keep moving.
- **No vetoes.** Spending coins reorders the queue — it can never stop a game from eventually being played.
- **The skip jar has teeth.** Skipping costs real coins and needs a *majority* of the people who
  actually played the game, so one impatient person can't end the week for everyone. A game also has
  to run for a minimum time before it can be skipped at all.
- **Every coin is accounted for.** Each movement is written to a permanent, tamper-evident ledger —
  nothing is ever silently edited or deleted.

---

## For the technically curious

Coiny is a personal portfolio project, built the way a real service would be rather than as a
throwaway script — and developed **spec-first**: every feature began as a written specification
(see [`specs/`](specs/)), which was clarified, planned, and broken into tasks before any code was
written. The branch-per-feature history (`001-foundation-skeleton` → `006-skip-jar`) is that process
on the record.

**Built with**

- **Java 21** and **Spring Boot 3** — application framework and dependency injection
- **JDA 5** — the Discord gateway / REST client
- **PostgreSQL 17** — the one and only datastore, in every environment (no in-memory shortcuts)
- **Spring Data JPA / Hibernate** + **Flyway** — persistence and versioned database migrations
- **Docker** (a single multi-stage image) + **Docker Compose** — the app and its database come up
  together; dev and prod differ only by configuration, never by a forked Dockerfile
- **GitHub Actions** — every change is built and fully tested in CI

**How it's organised** — a hexagonal (ports-and-adapters) architecture that keeps the rules of the
game independent of Discord and the database:

- `bot.domain` — pure business rules (coins, queue order, skip thresholds); no framework code
- `bot.application` — use-case services; the only layer allowed to touch the database
- `bot.infrastructure` — the plumbing: Discord wiring, repositories, the weekly scheduler, the cover-art client
- `bot.discord.command` — thin slash-command handlers

**Details worth a look**

- **Tested against a real database.** Integration tests spin up a genuine throwaway PostgreSQL with
  **Testcontainers** — no mock database to hide bugs.
- **Money-grade correctness.** The coin economy is a double-entry, append-only ledger, and every
  operation is idempotent: a double-click or a retry never charges anyone twice.
- **Cover art** for queued games is looked up from **IGDB** based on what each player is currently
  playing. It's optional — without IGDB credentials the queue simply shows game names.

## Run it yourself

You only need **Docker Desktop** (running) and a **Discord bot token** — you don't have to install
Java, a database, or anything else; Docker handles all of it.

```powershell
# Windows (PowerShell)
.\scripts\up-dev.ps1
```

```bash
# Linux / macOS
./scripts/up-dev.sh
```

The script asks whether to reset the local database, then prompts for a database password and your
Discord bot token (hidden as you type), then builds and starts everything. Once Coiny shows as
**online** in your server, try `/balance`.

> **Heads-up:** because Coiny reads the game you're playing from your Discord status, it needs the
> **Presence Intent** and **Server Members Intent** switched on for your bot in the
> [Discord Developer Portal](https://discord.com/developers/applications).

### Running the tests

```bash
./mvnw verify
```

Needs Docker running (for Testcontainers); no secrets required.

---

<sub>A personal portfolio project by Leandro Borgnia · Java 21 · Spring Boot · JDA · PostgreSQL · spec-driven with GitHub Spec Kit</sub>
