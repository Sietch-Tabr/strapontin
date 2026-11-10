/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.http;

/**
 * Valeurs partagées par les clients de sources : identité du robot, adresses de
 * base des services appelés par plusieurs classes, codes HTTP qui signifient un
 * refus ou une limitation. Chacune est définie ici une seule fois.
 * @since 3.1.4
 * @author Kwaite
 */
// Classe de constantes : aucune méthode, par construction.
@SuppressWarnings("PMD.DataClass")
public final class ConstantesSources {

  /**
   * Identité honnête du robot, déclarée à chaque site et billetterie
   * interrogés.
   */
  public static final String USER_AGENT_ROBOT = "StrapontinBot/1.0 (+https://strapontin.kwaite.fr)";

  /** Adresse de base de l'API Discovery de Ticketmaster. */
  public static final String TICKETMASTER_BASE_URL = "https://app.ticketmaster.com/discovery/v2";

  /** Adresse de base de l'API de recherche de Deezer. */
  public static final String DEEZER_BASE_URL = "https://api.deezer.com";

  /** Code HTTP d'un accès refusé. */
  public static final int STATUT_ACCES_REFUSE = 403;

  /** Code HTTP d'une limitation de débit. */
  public static final int STATUT_LIMITATION = 429;

  private ConstantesSources() {}
}
