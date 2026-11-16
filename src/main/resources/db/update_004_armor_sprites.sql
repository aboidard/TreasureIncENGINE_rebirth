-- Armors drawn as helmets.
-- To run once on an existing database: the sprites 130, 133 and 134 (helmets) and 132 (boots) were given to
-- armors (Equipment.Kind). The armors generated with them keep their slot, their name and their stats, and get
-- the sprite of an armor instead. Nothing to do on a new database.

BEGIN;

UPDATE public.items
SET graphics = CASE WHEN id % 2 = 0 THEN 126 ELSE 131 END
WHERE graphics IN (130, 132, 133, 134)
  AND stats ->> 'slot' = '2';

COMMIT;
