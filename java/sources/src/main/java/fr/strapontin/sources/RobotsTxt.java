/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Règles {@code Disallow} d'un {@code robots.txt} qui visent notre robot ou tous les robots : le
 * groupe le plus précis (notre nom) l'emporte sur le groupe générique.
 * @since 3.0.6
 * @author Kwaite
 */
final class RobotsTxt {

  /** Aucune restriction (fichier absent). */
  static final RobotsTxt LIBRE = new RobotsTxt(List.of());

  private static final String AGENT_GENERIQUE = "*";

  private final List<String> interdits;

  private RobotsTxt(final List<String> interdits) {
    this.interdits = List.copyOf(interdits);
  }

  /**
   * @param contenu texte du fichier {@code robots.txt}
   * @param agent nom du robot, sans version (ex. « strapontinbot »)
   * @return les règles applicables au robot
   */
  /* package-private : appelé par ClientSiteSalle (même package). */
  static RobotsTxt analyser(final String contenu, final String agent) {
    final String agentMinuscules = agent.toLowerCase(Locale.ROOT);
    final List<String> generiques = new ArrayList<>();
    final List<String> specifiques = new ArrayList<>();
    final List<String> agentsDuGroupe = new ArrayList<>();
    boolean regleVueDansLeGroupe = false;
    for (final String ligneBrute : contenu.split("\\R")) {
      final String ligne = ligneBrute.replaceFirst("#.*$", "").strip();
      final int deuxPoints = ligne.indexOf(':');
      if (deuxPoints < 0) {
        continue;
      }
      final String cle = ligne.substring(0, deuxPoints).strip().toLowerCase(Locale.ROOT);
      final String valeur = ligne.substring(deuxPoints + 1).strip();
      if ("user-agent".equals(cle)) {
        if (regleVueDansLeGroupe) {
          agentsDuGroupe.clear();
          regleVueDansLeGroupe = false;
        }
        agentsDuGroupe.add(valeur.toLowerCase(Locale.ROOT));
      } else if ("disallow".equals(cle)) {
        regleVueDansLeGroupe = true;
        if (!valeur.isEmpty()) {
          ajouterSelonAgents(agentsDuGroupe, agentMinuscules, valeur, generiques, specifiques);
        }
      } else if ("allow".equals(cle) || "crawl-delay".equals(cle)) {
        regleVueDansLeGroupe = true;
      }
    }
    return new RobotsTxt(specifiques.isEmpty() ? generiques : specifiques);
  }

  private static void ajouterSelonAgents(
      final List<String> agents,
      final String agent,
      final String chemin,
      final List<String> generiques,
      final List<String> specifiques) {
    if (agents.contains(agent)) {
      specifiques.add(chemin);
    } else if (agents.contains(AGENT_GENERIQUE)) {
      generiques.add(chemin);
    }
  }

  /**
   * @param chemin chemin de la page demandée (sans l'hôte), éventuellement avec sa requête
   * @return {@code false} si une règle {@code Disallow} le vise
   */
  /* package-private : appelé par ClientSiteSalle (même package). */
  boolean autorise(final String chemin) {
    return interdits.stream().noneMatch(chemin::startsWith);
  }
}
