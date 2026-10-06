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
        "Deux salles semblent désigner le même lieu : les fusionner depuis le référentiel, ou les"
            + " déclarer distinctes.");
    ajouter(
        "meme_lieu_salles",
        "Deux salles au même endroit",
        "Deux salles ont des lieux sources à la même position : les fusionner si c'est le même"
            + " lieu, sinon les déclarer distinctes.");
    ajouter(
        "position_wikidata_incoherente",
        "Wikidata d'un homonyme",
        "La position Wikidata de cette salle est loin de ses lieux sources : réinitialiser son"
            + " enrichissement depuis le référentiel.");
    ajouter(
        "position_hors_departement",
        "Salle hors de son département",
        "La position de cette salle est loin de son département : corriger son département ou"
            + " réinitialiser son enrichissement depuis le référentiel.");
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
        "notifications_non_envoyees",
        "Notifications non envoyées",
        "Des concerts éligibles n'ont jamais été notifiés à ce compte. La cause (aucun canal"
            + " joignable ou envoi en échec) et l'action possible sont indiquées dans le détail.");
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
            + " être fusionnées automatiquement.");
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
        "Les coordonnées Wikidata de la salle s'écartent de celles de ses concerts.");
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
