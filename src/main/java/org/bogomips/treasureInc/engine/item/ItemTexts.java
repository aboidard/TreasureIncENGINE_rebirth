package org.bogomips.treasureInc.engine.item;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Text catalogues for generated items and expeditions.
 * Descriptions are stored in Redis as their index in the rarity's list: only ever append to those lists.
 */
public final class ItemTexts {

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

    private static final List<String> COMMON_ITEM = List.of(
            "babiole,F,la", "breloque,F,la", "bibelot,M,le", "broutille,F,la", "bijou,M,le", "porte-bonheur,M,le",
            "bricole,F,la", "bronze,M,le", "curiosité,F,la", "étrangeté,F,l'", "ornement,M,l'", "bizarrerie,F,l'",
            "décoration,F,la");

    private static final List<String> RARE_ITEM = List.of(
            "bijou,M,le", "porte-bonheur,M,le", "bronze,M,le", "ornement,M,l'", "décoration,F,la",
            "chef-d'oeuvre,M,le");

    private static final List<String> COMMON_ADJECTIVE = List.of(
            "insignifiant,insignifiante", "inintéressant,inintéressante", "terne", "fade", "livide", "quelconque",
            "usé,usée", "grisâtre", "délavé,délavée", "banal,banale", "ordinaire", "courant,courante",
            "habituel,habituelle");

    private static final List<String> UNCOMMON_ADJECTIVE = List.of(
            "original,originale", "étonnant,étonnante", "étrange", "atypique", "inhabituel,inhabituelle",
            "singulier,singulière", "anormal,anormale", "inusuel,inusuelle", "insolite");

    private static final List<String> RARE_ADJECTIVE = List.of(
            "intéressant,intéressante", "brillant,brillante", "admirable", "précieux,précieuse", "remarquable",
            "surprenant,surprenante", "rare", "rarissime");

    private static final List<String> EPIC_ADJECTIVE = List.of(
            "incroyable", "étincelant,étincelante", "exceptionnel,exceptionnelle", "spectaculaire", "d'exception",
            "sans pareil,sans pareille", "parfait,parfaite", "sensationnel,sensationnelle",
            "prodigieux,prodigieuse", "parfait,parfaite");

    private static final List<String> LEGENDARY_ADJECTIVE = List.of(
            "légendaire", "fabuleux,fabuleuse", "mithique", "fantastique", "transcendant,transcendante");

    private static final List<String> UNCOMMON_DESCRIPTION = List.of(
            "C'est un objet qui sort de l'ordinaire.",
            "Il attire l'attention par sa singularité.",
            "On ne voit pas souvent ce genre d'objet.",
            "Il a quelque chose de spécial.",
            "Pas commun, mais pas extraordinaire non plus.");

    private static final List<String> RARE_DESCRIPTION = List.of(
            "Un objet rare et précieux.",
            "Il se distingue par sa qualité exceptionnelle.",
            "Peu d'exemplaires existent dans le monde.",
            "Il est recherché par les collectionneurs.",
            "Sa rareté en fait un objet de valeur.");

    private static final List<String> EPIC_DESCRIPTION = List.of(
            "Un objet épique digne des légendes.",
            "Il possède des caractéristiques hors du commun.",
            "Sa puissance est reconnue par tous.",
            "Il est au centre de nombreuses histoires.",
            "C'est une de mes plus belle pièce...",
            "Incroyable, mais vrai !",
            "J'ai dépeché un de mes meilleurs hommes pour aller chercher ça !",
            "Un véritable chef-d'œuvre.");

    private static final List<String> LEGENDARY_DESCRIPTION = List.of(
            "Un objet légendaire, unique en son genre.",
            "Il est entouré de mystère et de fascination.",
            "Sa renommée dépasse les frontières.",
            "Il est convoité par les plus grands aventuriers.",
            "Sa place est dans un musée !",
            "Un certain professeur Johanes l'a cherché toute sa vie.",
            "Nulle part dans le monde on ne trouve de tel objet.",
            "Un trésor inestimable.");

    private static final List<String> COMMON_DESCRIPTION = List.of(
            "Objet bien mais pas top, il aurait été inventé il y a longtemps avant JC.",
            "Mais qu'est ce que c'est que cette matière ? mais c'est... c'est...",
            "De fabrication artisanale, il a été réalisé grace à un mouvement manuel rotatif en s'aidant de la région axillaire.",
            "Mais commençons par le début, qu'est-ce qu'un pont suspendu ?",
            "C'est un objet qui a été fabriqué par un artisan, et qui a été utilisé par un artisan.",
            "Bon sang mais c'est bien sûr !",
            "2,21 gigawatts !",
            "Il y a une etiquette : 'Made in Mordak'",
            "Il y a une inscription : 'frottez moi'",
            "Il y a une inscription : 'mangez moi'",
            "Il y a une inscription : 'Lavage à la main uniquement'",
            "Il y a une inscription : 'ne pas mettre au micro-ondes'",
            "Il y a une inscription : 'ne pas mettre au four'",
            "Il y a une inscription : 'ne pas mettre au lave-vaisselle'",
            "Il y a une inscription : 'lavage 40 degrés maximum'",
            "J'ai trouvé ça dans un grenier, je ne sais pas ce que c'est...",
            "J'ai dépensé 1000 euros pour ça, c'est une arnaque !",
            "J'ai dépensé sans compter !",
            "On en aprend pas plus sur le lore avec cette description, on est pas dans dans Dark Souls !",
            "Semble onereux, mais en réalité très bon marché.",
            "Il y a une inscription : 'ne pas mettre au congélateur'",
            "Il y a une inscription : 'ne pas mettre au réfrigérateur'",
            "Ma grand-mère m'a donné ça, elle l'a trouvé dans un grenier.",
            "Mon grand-père en utilisait pour faire des crêpes.",
            "Utilisé par les plus grands chefs.",
            "Utile dans certaines situations.",
            "Parfaitement incassable regardez ! *crush* *crush* *crush*",
            "D'une solidité à toute épreuve.",
            "Incroyable, mais vrai !",
            "Incroyablement léger.",
            "Incroyablement lourd.",
            "Il y une étiquette sur laquelle on trouve un lien vers un site de vente en ligne.",
            "Avez-vous projeté de mettre du gameplay dans ce jeu ?",
            "Je vais vous faire une offre que vous ne pourrez refuser.",
            "Vous avez de la chance, c'est la dernière pièce de ce type.",
            "Un objet de collection.",
            "Elle se hâte trop, Burrhus, de triompher : J'embrasse mon rival, mais c'est pour l'étouffer.",
            "Il faut cultiver son jardin.",
            "Mais qu'est-ce que c'est que ça ?",
            "Ceci est absolument inutile.",
            "Je ne sais pas pourquoi j'ai acheté ça.",
            "Est-ce que quelqu'un peut m'expliquer à quoi ça sert ?",
            "Je parie que ça ne sert à rien.",
            "C'est juste pour le style.",
            "Ça pourrait être utile un jour, peut-être.");

    private ItemTexts() {
    }

    public static String expeditionName(RandomGenerator random) {
        String[] ex = pick(EXPEDITION, random).split(",");
        String[] adj = pick(EXPEDITION_ADJECTIVE, random).split(",");
        return ex[0] + " " + agree(adj, FEMININE.equals(ex[1]));
    }

    public static String itemName(Rarity rarity, RandomGenerator random) {
        List<String> adjectives;
        List<String> nouns;
        switch (rarity) {
            case UNCOMMON -> { adjectives = UNCOMMON_ADJECTIVE; nouns = COMMON_ITEM; }
            case RARE -> { adjectives = RARE_ADJECTIVE; nouns = RARE_ITEM; }
            case EPIC -> { adjectives = EPIC_ADJECTIVE; nouns = RARE_ITEM; }
            case LEGENDARY -> { adjectives = LEGENDARY_ADJECTIVE; nouns = RARE_ITEM; }
            default -> { adjectives = COMMON_ADJECTIVE; nouns = COMMON_ITEM; }
        }
        String[] adj = pick(adjectives, random).split(",");
        String[] item = pick(nouns, random).split(",");
        return item[0] + " " + agree(adj, FEMININE.equals(item[1]));
    }

    /** Random description id, valid for {@link #description(Rarity, int)} with the same rarity. */
    public static int randomDescriptionId(Rarity rarity, RandomGenerator random) {
        return random.nextInt(descriptions(rarity).size());
    }

    /** Text for a stored description id; null if the id is unknown. */
    public static String description(Rarity rarity, Integer id) {
        List<String> list = descriptions(rarity);
        return id == null || id < 0 || id >= list.size() ? null : list.get(id);
    }

    private static List<String> descriptions(Rarity rarity) {
        return switch (rarity) {
            case UNCOMMON -> UNCOMMON_DESCRIPTION;
            case RARE -> RARE_DESCRIPTION;
            case EPIC -> EPIC_DESCRIPTION;
            case LEGENDARY -> LEGENDARY_DESCRIPTION;
            default -> COMMON_DESCRIPTION;
        };
    }

    private static String agree(String[] adjective, boolean feminine) {
        return adjective.length > 1 && feminine ? adjective[1] : adjective[0];
    }

    private static String pick(List<String> list, RandomGenerator random) {
        return list.get(random.nextInt(list.size()));
    }
}
