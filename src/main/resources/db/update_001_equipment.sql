-- Equipment of the characters (branch features/Claude/add_equipment).
-- To run once on an existing database; create_db.sql of treasure-inc-conf already includes it.

BEGIN;

-- Stats of an equipable item, NULL for any other item.
-- {"slot": 3, "for": 5, "luk": 2, "spe1": "breakable"} : see the README ("Équipements").
ALTER TABLE public.items ADD COLUMN stats jsonb;

-- The characters of the users. equipment is the item id by slot number: {"2": 812, "3": 640}.
CREATE TABLE public.characters (
    id integer NOT NULL,
    user_id integer NOT NULL,
    num integer NOT NULL,
    equipment jsonb DEFAULT '{}'::jsonb NOT NULL,
    createdat timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updatedat timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE public.characters OWNER TO treasure_inc;

CREATE SEQUENCE public.characters_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE public.characters_id_seq OWNER TO treasure_inc;

ALTER SEQUENCE public.characters_id_seq OWNED BY public.characters.id;

ALTER TABLE ONLY public.characters ALTER COLUMN id SET DEFAULT nextval('public.characters_id_seq'::regclass);

ALTER TABLE ONLY public.characters
    ADD CONSTRAINT characters_pkey PRIMARY KEY (id);

-- one character per number for a user; also the index used to read the characters of a user
ALTER TABLE ONLY public.characters
    ADD CONSTRAINT characters_user_num_key UNIQUE (user_id, num);

-- Every existing user gets its 3 characters (the engine creates them for the users created afterwards).
INSERT INTO public.characters (user_id, num)
SELECT u.id, n.num
FROM public.users u
CROSS JOIN generate_series(1, 3) AS n(num);

COMMIT;
