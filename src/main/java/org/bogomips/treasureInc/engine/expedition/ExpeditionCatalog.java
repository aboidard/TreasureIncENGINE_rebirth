package org.bogomips.treasureInc.engine.expedition;

import java.util.List;
import java.util.random.RandomGenerator;

/** Text catalogue for expedition names, with the adjective agreeing in gender with the noun. */
final class ExpeditionCatalog {

    private static final String FEMININE = "F";

    private static final List<String> EXPEDITION = List.of(
            "Expédition,F,l'", "Aventure,F,l'", "Baroud,M,le", "Errance,F,l'", "Épisode,M,l'", "Épreuve,F,l'",
            "Péripétie,F,la", "Imprudence,F,l'", "Conquête,F,la", "Rencontre,F,la", "Mésaventure,F,la",
            "Équipée,F,l'", "Destin,M,le", "Entreprise,F,l'", "Odyssée,F,l'", "Évenement,M,l'");

    private static final List<String> EXPEDITION_ADJECTIVE = List.of(
            "douloureux,douloureuse", "incroyable", "fructueux,fructueuse", "enrichissant,enrichissante",
            "rémunérateur,rémunératrice", "lucratif,lucrative", "rentable", "salutaire", "fertile",
            "productif,productive", "intéressant,intéressante", "productif,productive", "juteux,juteuse",
            "fécond,féconde", "intarissable", "généreux,généreuse");

    private ExpeditionCatalog() {
    }

    static String name(RandomGenerator random) {
        String[] noun = pick(EXPEDITION, random).split(",");
        String[] adjective = pick(EXPEDITION_ADJECTIVE, random).split(",");
        boolean feminine = FEMININE.equals(noun[1]);
        return noun[0] + " " + (adjective.length > 1 && feminine ? adjective[1] : adjective[0]);
    }

    private static String pick(List<String> list, RandomGenerator random) {
        return list.get(random.nextInt(list.size()));
    }
}
