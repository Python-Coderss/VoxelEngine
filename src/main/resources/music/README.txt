GAME MUSIC — DYNAMIC CONTEXT POOLS
==================================

Music is not one flat loop: the game picks a pool from what is happening and
crossfades by switching tracks. Contexts, in priority order:

  combat/   hostile mobs within 10 blocks — fighting for your life
  danger/   hostile mobs within 24 blocks — something is stalking you
  night/    quiet dark hours, nothing nearby (sun below the horizon)
  calm/     safe daytime exploring (also the root-folder fallback)

Folder layout:
  src/main/resources/music/
    01_generic_calm.mp3      <- root files = the calm pool
    combat/                  <- drop tense MCSM scenes here
    danger/
    night/
    calm/                    <- optional; root files are used if absent

The game scans every folder (subfolders of any depth are fine). A context
folder with no real tracks yet falls back to the root pool, so music keeps
playing while you sort your collection.

Format: MP3, mono (-ac 1), ~96 kbps. HTML files renamed to .mp3 are treated
as placeholders: counted as "pending" at boot, never played, skipped without
error. Replace them one by one with real downloads and relaunch.

Converting a big uncompressed collection (WAV/FLAC/AIFF) in one batch:
      tools/convert_music.sh <input-dir>
Then move the resulting mp3s into the pools above (requires ffmpeg).

Sourcing note: only include audio you have the rights to bundle (your own
recordings, CC-licensed fan music, or rips you own for personal use). The
engine won't fetch MCSM / legacy-console / fan soundtrack files for you.
