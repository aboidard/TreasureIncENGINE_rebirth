-- The events of the game: expeditions limited in time or special, besides the locations of the story. Changed from
-- Maestro; the engine loads them in Redis when it starts. To run once on an existing database.

BEGIN;

-- code is the id of the event, used by the client and Maestro ([a-z0-9_-]). image is the name of a sprite of the
-- client (Resources/Events/<image>) or the URL of a picture. level is its difficulty, from 1 (easy) to 3 (hard):
-- what is met there; floors is the number of floors of its expeditions. The rewards are given when the chest of an
-- expedition is opened, the share of the floors finished when the character died on the way. maxperday is how many
-- expeditions a player may send there each day (0: no limit). The event is open from startat (at once when null)
-- until endat (for ever when null) while enabled. The times are UTC, like the other tables.
CREATE TABLE public.game_events (
    code character varying(40) PRIMARY KEY,
    name character varying(100) NOT NULL,
    description character varying(500),
    image character varying(500),
    level integer DEFAULT 1 NOT NULL CHECK (level BETWEEN 1 AND 3),
    floors integer DEFAULT 10 NOT NULL CHECK (floors BETWEEN 1 AND 10),
    rewardgems integer DEFAULT 0 NOT NULL CHECK (rewardgems >= 0),
    rewardmoney integer DEFAULT 0 NOT NULL CHECK (rewardmoney >= 0),
    rewarditems integer DEFAULT 0 NOT NULL CHECK (rewarditems BETWEEN 0 AND 15),
    maxperday integer DEFAULT 1 NOT NULL CHECK (maxperday >= 0),
    startat timestamp without time zone,
    endat timestamp without time zone,
    enabled boolean DEFAULT true NOT NULL,
    sortorder integer DEFAULT 0 NOT NULL,
    updatedat timestamp without time zone
);

ALTER TABLE public.game_events OWNER TO treasure_inc;

-- the launch event: once a day, 1000 gems
INSERT INTO public.game_events (code, name, description, image, level, floors, rewardgems, maxperday, updatedat)
VALUES ('lancement', 'Événement de lancement',
        'Pour fêter le lancement de Treasure Inc : une expédition par jour, 1000 gemmes à la clé !',
        'lancement', 1, 10, 1000, 1, now() AT TIME ZONE 'UTC');

COMMIT;
