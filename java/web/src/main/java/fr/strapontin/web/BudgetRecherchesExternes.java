/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.securite.LimiteurDebit;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Plafonds des recherches interactives qui interrogent un service externe
 * (artistes : Wikidata et Deezer ; salles : Nominatim) : un plafond global par
 * minute et par service, quel que soit le nombre d'utilisateurs, et un plafond
 * par utilisateur. Un dépassement global est consigné dans {@code logs_admin}.
 * @since 3.0.6
 * @author Kwaite
 */
@Component
public class BudgetRecherchesExternes {

  /**
   * Services externes interrogés depuis l'interface, avec leur plafond global
   * par minute.
   */
  public enum Service {
    /** Nominatim : 1 requête par seconde pour toute l'adresse, partagée avec le worker. */
    NOMINATIM("Nominatim", 20),
    /** API de recherche Wikidata. */
    WIKIDATA("Wikidata", 60),
    /** API de recherche Deezer (50 requêtes par 5 secondes autorisées). */
    DEEZER("Deezer", 60);

    private final String libelle;
    private final int parMinute;

    Service(final String libelle, final int parMinute) {
      this.libelle = libelle;
      this.parMinute = parMinute;
    }
  }

  private static final int MAX_RECHERCHES_UTILISATEUR_PAR_MINUTE = 15;
  private static final long DELAI_ENTRE_JOURNAUX_MS = Duration.ofMinutes(10).toMillis();
  private static final String CLE_GLOBALE = "global";

  private final LogAdminService logAdminService;
  private final Map<Service, LimiteurDebit> parService = new EnumMap<>(Service.class);
  private final Map<Service, AtomicLong> dernierJournalMs = new EnumMap<>(Service.class);
  private final LimiteurDebit parUtilisateur =
      new LimiteurDebit(
          MAX_RECHERCHES_UTILISATEUR_PAR_MINUTE,
          Duration.ofMinutes(1),
          Duration.ofMinutes(2),
          10_000);

  /**
   * @param logAdminService journalisation des dépassements
   */
  public BudgetRecherchesExternes(final LogAdminService logAdminService) {
    this.logAdminService = logAdminService;
    for (final Service service : Service.values()) {
      parService.put(
          service,
          new LimiteurDebit(service.parMinute, Duration.ofMinutes(1), Duration.ofMinutes(2), 1));
      dernierJournalMs.put(service, new AtomicLong(0));
    }
  }

  /**
   * Compte une recherche de l'utilisateur, tous services confondus.
   *
   * @param userId identifiant de l'utilisateur
   * @return {@code true} si la recherche est permise
   */
  public boolean autoriserUtilisateur(final String userId) {
    return parUtilisateur.autoriser(userId);
  }

  /**
   * Réserve un appel vers le service. Un refus rend la recherche sans
   * résultat externe plutôt que de saturer le service.
   *
   * @param service service à interroger
   * @return {@code true} si l'appel est dans le plafond global
   */
  public boolean consommer(final Service service) {
    if (parService.get(service).autoriser(CLE_GLOBALE)) {
      return true;
    }
    journaliserDepassement(service);
    return false;
  }

  private void journaliserDepassement(final Service service) {
    final AtomicLong dernier = dernierJournalMs.get(service);
    final long maintenant = System.currentTimeMillis();
    final long precedent = dernier.get();
    if (maintenant - precedent >= DELAI_ENTRE_JOURNAUX_MS
        && dernier.compareAndSet(precedent, maintenant)) {
      logAdminService.logAdmin(
          "warn",
          "recherche-externe",
          "Plafond de recherches atteint vers "
              + service.libelle
              + " ("
              + service.parMinute
              + " par minute) : recherches sans résultat externe");
    }
  }
}
