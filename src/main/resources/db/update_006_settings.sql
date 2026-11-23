-- The settings of the game, changed from Maestro: the maintenance and the oldest version of the Unity client allowed
-- to play. To run once on an existing database; create_db.sql of treasure-inc-conf already includes it.

BEGIN;

-- One row only (id = 1). While maintenance is true, the requests of the game are refused from maintenancestart (at
-- once when null) until maintenanceend (until it is switched off when null); maintenancereason is what the players
-- read. minclientversion is the oldest Application.version of the Unity client that may log in ('0': any).
-- The times are UTC, like the other tables.
CREATE TABLE public.app_settings (
    id integer PRIMARY KEY CHECK (id = 1),
    maintenance boolean DEFAULT false NOT NULL,
    maintenancereason character varying(500),
    maintenancestart timestamp without time zone,
    maintenanceend timestamp without time zone,
    minclientversion character varying(32) DEFAULT '0' NOT NULL,
    updatedat timestamp without time zone
);

ALTER TABLE public.app_settings OWNER TO treasure_inc;

INSERT INTO public.app_settings (id) VALUES (1);

COMMIT;
