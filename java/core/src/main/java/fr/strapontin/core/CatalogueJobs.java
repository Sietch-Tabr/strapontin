/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nom, rôle et thème de chaque job ({@code fetch_jobs}) pour le tableau de
 * bord admin ; un job absent est signalé et affiché « à classer ».
 * @since 3.0.2
 * @author Kwaite
 */
public final class CatalogueJobs {

  private static final Map<String, Job> JOBS = new LinkedHashMap<>();

  /** Thème métier d'un job, dans l'ordre d'affichage. */
  public enum Theme {
    /** Récupération des concerts auprès des billetteries. */
    RECUPERATION("Récupération des concerts"),
    /** Correspondances utilisateurs et notifications. */
    NOTIFICATIONS("Correspondances et notifications"),
    /** Contrôles, enrichissement et corrections du référentiel. */
    QUALITE("Qualité des données"),
    /** Comptes utilisateurs et droits. */
    COMPTES("Comptes et sécurité"),
    /** Suppression des données périmées. */
    PURGES("Purges");

    private final String nomAffiche;

    Theme(final String nomAffiche) {
      this.nomAffiche = nomAffiche;
    }

    /**
     * @return nom affiché du thème
     */
    public String libelle() {
      return nomAffiche;
    }
  }

  /**
   * Description d'un job.
   *
   * @param type identifiant technique ({@code fetch_jobs.type_job})
   * @param libelle nom affiché
   * @param description ce que fait le job, en une phrase
   * @param theme thème métier
   */
  public record Job(String type, String libelle, String description, Theme theme) {}

  static {
    ajouter(
        "fetch:artistes:batch",
        "Concerts des artistes suivis",
        "Recherche les concerts de chaque artiste suivi chez Ticketmaster et Eventim.",
        Theme.RECUPERATION);
    ajouter(
        "fetch:salles:all",
        "Concerts des salles suivies",
        "Recherche les concerts de chaque salle suivie, lieu par lieu.",
        Theme.RECUPERATION);
    ajouter(
        "fetch:region",
        "Concerts par département",
        "Parcourt les départements suivis pour alimenter la page Découverte.",
        Theme.RECUPERATION);
    ajouter(
        "fetch:sites-salles",
        "Concerts des sites de salles",
        "Lit chaque jour, en journée, le site des salles qui vendent hors Ticketmaster et"
            + " Eventim.",
        Theme.RECUPERATION);
    ajouter(
        "recalculer:matches",
        "Correspondances utilisateurs",
        "Recalcule pour chaque utilisateur les concerts de ses artistes et salles suivis.",
        Theme.NOTIFICATIONS);
    ajouter(
        "notifier:digest",
        "Envoi des notifications",
        "Envoie par email et push les nouveaux concerts de chaque utilisateur.",
        Theme.NOTIFICATIONS);
    ajouter(
        "reconciliation:nocturne",
        "Contrôle et enrichissement nocturnes",
        "Vérifie les identifiants Ticketmaster, enrichit salles et artistes (Wikidata, OSM,"
            + " Deezer), fusionne les doublons et contrôle l'intégrité des données.",
        Theme.QUALITE);
    ajouter(
        "suivi:cycles-jobs",
        "Surveillance des jobs",
        "Signale un job qui ne s'est plus exécuté depuis trop longtemps.",
        Theme.QUALITE);
    ajouter(
        "revoquer:admins",
        "Révocation des droits admin",
        "Retire les droits d'administration arrivés à échéance de leur préavis.",
        Theme.COMPTES);
    ajouter(
        "gerer:comptes-inactifs",
        "Comptes inactifs",
        "Relance puis supprime les comptes inutilisés depuis six mois.",
        Theme.COMPTES);
    ajouter(
        "gerer:invites-expires",
        "Invitations non activées",
        "Relance puis supprime les invitations jamais activées.",
        Theme.COMPTES);
    ajouter(
        "purger:concerts",
        "Concerts passés et disparus",
        "Supprime les concerts passés et signale ceux qui ne sont plus publiés.",
        Theme.PURGES);
    ajouter(
        "purger:concerts-non-confirmes",
        "Concerts non reconfirmés",
        "Signale pour décision les concerts absents des billetteries depuis longtemps.",
        Theme.PURGES);
    ajouter(
        "purger:concerts-hors-portee",
        "Concerts hors de portée",
        "Signale les concerts sans salle ni artiste jamais mis à jour.",
        Theme.PURGES);
    ajouter(
        "purger:salles",
        "Salles orphelines",
        "Supprime les salles sans concert à venir ni utilisateur qui les suit.",
        Theme.PURGES);
    ajouter(
        "purger:artistes",
        "Artistes orphelins",
        "Supprime les artistes sans concert à venir ni utilisateur qui les suit.",
        Theme.PURGES);
    ajouter(
        "purger:logs-admin",
        "Journaux anciens",
        "Supprime les journaux d'administration de plus de 30 jours.",
        Theme.PURGES);
  }

  private CatalogueJobs() {}

  private static void ajouter(
      final String type, final String libelle, final String description, final Theme theme) {
    JOBS.put(type, new Job(type, libelle, description, theme));
  }

  /**
   * @param type identifiant technique du job
   * @return sa description, vide si le job n'est pas répertorié
   */
  public static Optional<Job> decrire(final String type) {
    return Optional.ofNullable(JOBS.get(type));
  }

  /**
   * @return tous les jobs répertoriés, groupés par thème dans l'ordre d'affichage
   */
  public static List<Job> tous() {
    return JOBS.values().stream()
        .sorted((a, b) -> Integer.compare(a.theme().ordinal(), b.theme().ordinal()))
        .toList();
  }
}
