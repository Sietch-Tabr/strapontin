/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.sites;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Règles {@code Disallow} d'un {@code robots.txt} qui visent notre robot
 * ou tous les robots : le groupe le plus précis (notre nom) l'emporte.
 * @since 3.0.6
 * @author Kwaite
 */
final class RobotsTxt {

  /** Aucune restriction (fichier absent). */
  /* package-private : valeur par défaut de ClientSiteSalle (même package). */
  static final RobotsTxt LIBRE = new RobotsTxt(List.of());

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
    final LectureGroupes lecture = new LectureGroupes(agent.toLowerCase(Locale.ROOT));
    for (final String ligne : contenu.split("\\R")) {
      lecture.lire(ligne);
    }
    return new RobotsTxt(lecture.regles());
  }

  /**
   * @param chemin chemin de la page demandée (sans l'hôte), avec sa requête
   * @return {@code false} si une règle {@code Disallow} le vise
   */
  /* package-private : appelé par ClientSiteSalle (même package). */
  boolean autorise(final String chemin) {
    return interdits.stream().noneMatch(chemin::startsWith);
  }

  /** Lecture ligne à ligne : les agents d'un groupe, puis ses règles. */
  private static final class LectureGroupes {
    private static final String AGENT_GENERIQUE = "*";
    private static final String DIRECTIVE_AGENT = "user-agent";
    private static final String DIRECTIVE_INTERDIT = "disallow";
    private static final String DIRECTIVE_AUTORISE = "allow";
    private static final String DIRECTIVE_DELAI = "crawl-delay";

    private final String agent;
    private final List<String> generiques = new ArrayList<>();
    private final List<String> specifiques = new ArrayList<>();
    private final List<String> agentsDuGroupe = new ArrayList<>();
    private boolean regleVueDansLeGroupe;

    private LectureGroupes(final String agent) {
      this.agent = agent;
    }

    private void lire(final String ligneBrute) {
      final String ligne = ligneBrute.replaceFirst("#.*$", "").strip();
      final int deuxPoints = ligne.indexOf(':');
      if (deuxPoints < 0) {
        return;
      }
      final String cle = ligne.substring(0, deuxPoints).strip().toLowerCase(Locale.ROOT);
      final String valeur = ligne.substring(deuxPoints + 1).strip();
      if (DIRECTIVE_AGENT.equals(cle)) {
        ouvrirAgent(valeur);
      } else if (DIRECTIVE_INTERDIT.equals(cle)) {
        interdire(valeur);
      } else if (DIRECTIVE_AUTORISE.equals(cle) || DIRECTIVE_DELAI.equals(cle)) {
        regleVueDansLeGroupe = true;
      }
    }

    // Un nouvel agent après des règles ouvre un nouveau groupe.
    private void ouvrirAgent(final String valeur) {
      if (regleVueDansLeGroupe) {
        agentsDuGroupe.clear();
        regleVueDansLeGroupe = false;
      }
      agentsDuGroupe.add(valeur.toLowerCase(Locale.ROOT));
    }

    private void interdire(final String chemin) {
      regleVueDansLeGroupe = true;
      if (chemin.isEmpty()) {
        return;
      }
      if (agentsDuGroupe.contains(agent)) {
        specifiques.add(chemin);
      } else if (agentsDuGroupe.contains(AGENT_GENERIQUE)) {
        generiques.add(chemin);
      }
    }

    private List<String> regles() {
      return specifiques.isEmpty() ? generiques : specifiques;
    }
  }
}
