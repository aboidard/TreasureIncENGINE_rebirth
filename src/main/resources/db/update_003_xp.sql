-- Levels of the characters.
-- To run once on an existing database; create_db.sql of treasure-inc-conf already includes it.

BEGIN;

-- The experience points the character earned in expeditions. Its level (1 to 100) and its stats are computed
-- from them by the engine (Leveling), so they are not stored.
ALTER TABLE public.characters ADD COLUMN xp integer DEFAULT 0 NOT NULL;

COMMIT;
