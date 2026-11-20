-- Gems (the premium currency) and the gift box.
-- To run once on an existing database; create_db.sql of treasure-inc-conf already includes it.

BEGIN;

-- The gems of the user: every user starts with none. They pay the summons and are won with the daily bonus quest
-- and the gifts.
ALTER TABLE public.users ADD COLUMN gems integer DEFAULT 0 NOT NULL;

-- The gift box: a gift waits until the user takes it (claimedat), unless it expires first (expiresat, null when it
-- never expires). Its reward is money, gems and items generated when it is taken: rarity is the rarity of these
-- items, null when each one is drawn as in a chest. batch identifies the sending the gift comes from.
-- The times are UTC, like the other tables.
CREATE TABLE public.gifts (
    id serial PRIMARY KEY,
    user_id integer NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    title character varying(120) NOT NULL,
    money integer DEFAULT 0 NOT NULL,
    gems integer DEFAULT 0 NOT NULL,
    items integer DEFAULT 0 NOT NULL,
    rarity character varying(16),
    batch character varying(36),
    createdat timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expiresat timestamp without time zone,
    claimedat timestamp without time zone
);

ALTER TABLE public.gifts OWNER TO treasure_inc;

-- the box of a user only reads the gifts not taken yet
CREATE INDEX gifts_user_pending_idx ON public.gifts (user_id) WHERE claimedat IS NULL;

COMMIT;
