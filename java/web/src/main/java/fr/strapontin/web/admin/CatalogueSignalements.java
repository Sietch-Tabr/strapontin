/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nom et explication de chaque type de signalement, pour qu'une ligne de
 * l'onglet « À traiter » se comprenne sans connaissance technique.
 * @since 3.0.2
 * @author Kwaite
 */
public final class CatalogueSignalements {

  private static final Map<String, TypeSignalement> TYPES = new LinkedHashMap<>();

  /**
   * Description d'un type de signalement.
   *
   * @param type identifiant technique
   * @param libelle nom affiché
   * @param explication ce que signifie le signalement et comment le traiter
   */
  public record TypeSignalement(String type, String libelle, String explication) {}

  static {
    ajouter(
        "contamination_salle",
        "Salle mélangeant plusieurs lieux",
        "Des lieux sources aux noms différents sont rattachés à cette salle. Répartir la salle si"
            + " ce sont des lieux distincts, sinon déclarer un faux positif.");
    ajouter(
        "dispersion_sources_salle",
        "Lieux sources éloignés",
        "Les lieux sources de cette salle sont dans des quartiers ou des villes différents :"
            + " répartir la salle entre les lieux réels.");
    ajouter(
        "salle_multi_departements",
        "Concerts sur plusieurs départements",
        "Des concerts de départements différents sont rattachés à cette salle : répartir la"
            + " salle entre les lieux réels.");
    ajouter(
        "salle_suivie_contaminee",
        "Salle contaminée suivie",
        "Des utilisateurs suivent cette salle signalée contaminée : à traiter en priorité.");
    ajouter(
        "doublon_salle",
        "Doublon de salle probable",
        "Deux salles semblent désigner le même lieu : les comparer puis les fusionner, ou les"
            + " déclarer distinctes.");
    ajouter(
        "meme_lieu_salles",
        "Deux salles au même endroit",
        "Deux salles ont des lieux sources à la même position : les comparer puis les fusionner"
            + " si c'est le même lieu, sinon marquer l'anomalie comme traitée.");
    ajouter(
        "position_wikidata_incoherente",
        "Wikidata d'un homonyme",
        "L'élément Wikidata de cette salle est situé loin de ses lieux sources (sa position est"
            + " réalignée chaque nuit sur l'élément) : c'est l'élément d'un autre lieu,"
            + " réinitialiser son enrichissement depuis le référentiel.");
    ajouter(
        "position_hors_departement",
        "Salle hors de son département",
        "La position de cette salle est loin de son département : corriger son département ou"
            + " réinitialiser son enrichissement depuis le référentiel.");
    ajouter(
        "position_source_eloignee",
        "Position de source éloignée de Wikidata",
        "La position de la salle, publiée par une billetterie, est à plus d'un kilomètre de son"
            + " élément Wikidata (écart, positions et lieux sources indiqués). Ouvrir l'élément :"
            + " s'il désigne bien la salle, aligner sa position sur Wikidata ; sinon réinitialiser"
            + " son enrichissement depuis le référentiel. La position d'un lieu source est"
            + " réécrite par la billetterie à chaque cycle : elle ne se corrige pas à la main.");
    ajouter(
        "ville_salle_divergente",
        "Ville de la salle contredite par sa position",
        "La position de cette salle, publiée par une billetterie, tombe dans une autre commune que"
            + " sa ville, alors que ses concerts nomment sa ville actuelle : la ville est gardée."
            + " Vérifier l'adresse de la salle ; le signalement se referme seul quand la ville et"
            + " la position s'accordent.");
    ajouter(
        "necessite_intervention",
        "Artiste Ticketmaster douteux",
        "Le nom de l'artiste chez Ticketmaster ne correspond plus à l'artiste suivi : corriger son"
            + " identifiant ou accepter ce nom.");
    ajouter(
        "date_festival_ecartee",
        "Date de festival attribuée à tort",
        "Ce concert vient d'un produit Eventim qui n'est plus retenu pour cet artiste (soirée"
            + " d'un festival où il ne joue pas) : vérifier le programme puis supprimer le"
            + " concert.");
    ajouter(
        "concert_double_salles_distinctes",
        "Concert en double sur deux salles distinctes",
        "Le même concert existe sur deux salles déclarées distinctes : un lieu de billetterie"
            + " est probablement resté sur la mauvaise salle. Répartir ses lieux, puis supprimer"
            + " le concert en trop.");
    ajouter(
        "creneau_double_meme_source",
        "Deux fiches au même créneau",
        "Deux fiches de la même billetterie occupent la même salle au même créneau : vérifier le"
            + " calendrier de la salle. Si un seul concert y est annoncé, supprimer la fiche en"
            + " trop depuis les concerts ; sinon marquer comme traité.");
    ajouter(
        "produit_eventim_double",
        "Un produit Eventim sur deux fiches",
        "Le même produit Eventim est porté par deux fiches du même jour dans la même salle, que la"
            + " fusion automatique n'a pas réunies (heures éloignées, artistes différents, plus de"
            + " deux fiches). Vérifier le calendrier de la salle : supprimer la fiche en trop"
            + " depuis les concerts, ou marquer comme traité si ce sont deux séances.");
    ajouter(
        "salle_sans_ville",
        "Salle sans commune",
        "Cette salle a des concerts à venir mais aucune commune : leurs fiches s'affichent sans"
            + " ville. L'enrichissement nocturne ne l'a pas trouvée : renseigner la commune depuis"
            + " le référentiel des salles ; le signalement se referme seul.");
    ajouter(
        "fiches_figees",
        "Fiches à venir sans mise à jour",
        "Des fiches à venir du périmètre des utilisateurs n'ont été revues par aucun fetch depuis"
            + " deux semaines, sans absence comptée : vérifier que la source les interroge"
            + " (départements, classements, salles) ; le signalement se referme seul.");
    ajouter(
        "departement_sans_concert",
        "Département sans concert",
        "Aucun concert à venir dans ce département : aucune de nos billetteries n'y programme ou"
            + " ne le couvre. Marquer comme traité une fois l'absence de couverture reconnue ; le"
            + " département ne sera signalé de nouveau que s'il retrouve des concerts puis les"
            + " perd.");
    ajouter(
        "notifications_non_envoyees",
        "Notifications non envoyées",
        "Des concerts éligibles n'ont pas été notifiés à ce compte alors qu'un canal est"
            + " joignable (email actif ou appareil abonné au push) : vérifier l'envoi dans les"
            + " journaux. La date indiquée est celle du plus ancien concert en attente pour ce"
            + " compte.");
    ajouter(
        "sessions_sans_appareil",
        "Sessions sans appareil",
        "Des sessions connectées n'ont pas d'appareil enregistré, invisibles dans la gestion des"
            + " appareils.");
    ajouter(
        "comptes_incoherents",
        "Comptes incohérents",
        "Un compte activé n'a pas d'identifiant de connexion unique haché en bcrypt.");
    ajouter(
        "secret_totp_non_chiffre",
        "Secret TOTP non chiffré",
        "Le secret du second facteur d'un compte est stocké en clair ou illisible : le"
            + " redémarrage du site le chiffre, sinon réenrôler le TOTP du compte.");
    ajouter(
        "identifiant_externe_autre_artiste",
        "Identifiant externe d'un autre artiste",
        "L'identifiant enregistré chez une billetterie désigne un autre artiste que celui suivi :"
            + " le retirer de la fiche de l'artiste.");
    ajouter(
        "seance_dans_deux_salles",
        "Même séance publiée deux fois",
        "Un même artiste est déclaré le même jour, à des heures proches, dans une salle et sa"
            + " sous-salle, dans deux salles d'une même commune ou deux fois dans une même salle :"
            + " une séance déplacée peut être restée publiée sous l'ancienne salle.");
    ajouter(
        "hommage_rattache_artiste",
        "Hommage rattaché à l'artiste imité",
        "L'adresse de la séance annonce un hommage (tribute) mais la fiche est rattachée à"
            + " l'artiste imité : ses abonnés seraient prévenus à tort.");
    ajouter(
        "annulation_une_billetterie",
        "Annulation vue par une seule billetterie",
        "Une séance à venir est annulée par une billetterie alors qu'une autre la présente encore"
            + " : vérifier sur le site de la salle.");
    ajouter(
        "envois_push_en_panne",
        "Envois push en panne",
        "Tous les envois vers un même service de notification échouent depuis plusieurs jours"
            + " pour plusieurs comptes : la cause est probablement l'application, aucun appareil"
            + " n'est retiré tant que la panne dure.");
    ajouter(
        "orphelins_fk",
        "Références orphelines",
        "Des lignes référencent une salle, un artiste ou un concert supprimé.");
    ajouter(
        "migration_flyway_echec",
        "Migration en échec",
        "Une migration de base de données a échoué : vérifier le dernier déploiement.");
    ajouter(
        "identifiant_externe_duplique",
        "Identifiant externe dupliqué",
        "Un même identifiant Wikidata ou Deezer est porté par plusieurs salles ou artistes.");
    ajouter(
        "incoherence_dept_salle",
        "Département de salle incohérent",
        "Le département de la salle diffère de celui de la majorité de ses concerts.");
    ajouter(
        "cycle_salle_parent",
        "Hiérarchie de salles en boucle",
        "Une salle est indirectement sa propre sous-salle.");
    ajouter(
        "evenement_orphelin_ancien",
        "Concert sans salle ni artiste",
        "Un concert n'est rattaché ni à une salle ni à un artiste depuis 14 jours.");
    ajouter(
        "chute_resultats_salle",
        "Chute de concerts d'un lieu",
        "Un lieu renvoie brutalement beaucoup moins de concerts : billetterie changée ou lieu"
            + " renommé chez la source.");
    ajouter(
        "collision_wikidata_salle",
        "Wikidata partagé par deux salles",
        "Deux salles se voient attribuer le même élément Wikidata sans être assez proches pour"
            + " être fusionnées automatiquement. Ouvrir l'élément pour juger : s'il désigne le"
            + " même lieu, les comparer puis les fusionner ; sinon réinitialiser l'enrichissement"
            + " de la salle signalée depuis le référentiel.");
    ajouter(
        "collision_wikidata_artiste",
        "Wikidata partagé par deux artistes",
        "Deux artistes se voient attribuer le même élément Wikidata : doublon ou homonyme.");
    ajouter(
        "wikidata_ambiguite",
        "Wikidata ambigu",
        "Plusieurs éléments Wikidata correspondent : aucun n'a été retenu automatiquement.");
    ajouter(
        "wikidata_coordonnees_divergentes",
        "Coordonnées Wikidata divergentes",
        "Un élément Wikidata correspondant au nom de la salle a été écarté, car sa position"
            + " s'écarte de celle de la salle (écart indiqué). L'ouvrir pour juger : si c'est bien"
            + " la salle, sa position enregistrée est probablement fausse ; sinon rien à faire, la"
            + " salle reste sans élément Wikidata.");
    ajouter(
        "artistes_meme_nom_sans_espaces",
        "Artistes au même nom, à l'espace près",
        "Deux artistes ne diffèrent que par les espaces de leur nom (« Krash Karma » et"
            + " « Krashkarma ») : probablement le même, à fusionner depuis le référentiel des"
            + " artistes ; sinon marquer l'anomalie comme traitée.");
    ajouter(
        "source_orpheline",
        "Source sans concert",
        "Un identifiant source n'a jamais produit de concert : mapping probablement erroné.");
  }

  private CatalogueSignalements() {}

  private static void ajouter(final String type, final String libelle, final String explication) {
    TYPES.put(type, new TypeSignalement(type, libelle, explication));
  }

  /**
   * @return tous les types décrits
   */
  public static List<TypeSignalement> tous() {
    return List.copyOf(TYPES.values());
  }

  /**
   * @param type identifiant technique
   * @return true si le type est décrit
   */
  public static boolean estDecrit(final String type) {
    return TYPES.containsKey(type);
  }
}
