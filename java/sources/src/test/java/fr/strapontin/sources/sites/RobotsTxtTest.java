/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.sites;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests de {@link RobotsTxt} sur les fichiers réels des sites de salles.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class RobotsTxtTest {

  private static final String AGENT = "strapontinbot";

  @Test
  void autorise_robotsDuTriton_interditLesZonesPriveesSeulement() {
    final RobotsTxt regles =
        RobotsTxt.analyser(
            """
            User-agent: *
            Allow: /
            Disallow: /admin
            Disallow: /espace
            Sitemap: https://letriton.com/sitemap.xml
            """,
            AGENT);

    assertTrue(regles.autorise("/agenda"));
    assertFalse(regles.autorise("/admin/utilisateurs"));
    assertFalse(regles.autorise("/espace"));
  }

  @Test
  void autorise_groupeNommantNotreRobot_primeSurLeGroupeGenerique() {
    final RobotsTxt regles =
        RobotsTxt.analyser(
            """
            User-agent: *
            Disallow: /

            User-agent: StrapontinBot
            Disallow: /prive
            """,
            AGENT);

    assertTrue(regles.autorise("/agenda"));
    assertFalse(regles.autorise("/prive/x"));
  }

  @Test
  void autorise_disallowVide_nInterditRien() {
    final RobotsTxt regles = RobotsTxt.analyser("User-agent : *\nDisallow :\n", AGENT);

    assertTrue(regles.autorise("/n-importe-quoi"));
  }

  @Test
  void autorise_robotsCommentaireSeul_nInterditRien() {
    final RobotsTxt regles =
        RobotsTxt.analyser("# This file is to prevent the crawling and indexing\n", AGENT);

    assertTrue(regles.autorise("/agenda"));
  }

  @Test
  void autorise_cheminAvecRequete_respecteLePrefixeInterdit() {
    final RobotsTxt regles =
        RobotsTxt.analyser("User-agent: *\nDisallow: /*?token=\nDisallow: /compte\n", AGENT);

    assertFalse(regles.autorise("/compte?x=1"));
    assertTrue(regles.autorise("/fr/concerts"));
  }

  @Test
  void libre_nInterditRien() {
    assertTrue(RobotsTxt.LIBRE.autorise("/tout"));
  }
}
