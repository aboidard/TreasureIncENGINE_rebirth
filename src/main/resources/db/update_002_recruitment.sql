-- Recruitment of the characters (portals and draws).
-- To run once on an existing database; create_db.sql of treasure-inc-conf already includes it.

BEGIN;

-- How many times the user obtained the character: 1, plus one for each duplicate drawn.
-- num is now the number of the character in the catalog of the engine (CharacterCatalog): the characters 1 to 3
-- are those every user starts with, so the existing rows keep their meaning.
ALTER TABLE public.characters ADD COLUMN copies integer DEFAULT 1 NOT NULL;

COMMIT;
