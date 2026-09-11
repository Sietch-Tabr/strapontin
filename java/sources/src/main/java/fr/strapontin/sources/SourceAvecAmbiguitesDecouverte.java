/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.Set;
import java.util.UUID;

/**
 * Capacité optionnelle d'une source à signaler les entités pour lesquelles la découverte a été
 * explicitement abandonnée sur ambiguïté (ex. homonyme humain/groupe côté {@code
 * WikidataArtisteSource}) — sans cette distinction, une ambiguïté détectée est indiscernable d'une
 * non-découverte ordinaire (#1194).
 *
 * @since 2.9.0
 * @author Kwaite
 */
public interface SourceAvecAmbiguitesDecouverte {

  /**
   * Retourne les identifiants signalés comme ambigus depuis le dernier prélèvement et remet
   * l'ensemble à zéro.
   *
   * @return les identifiants ambigus depuis le dernier prélèvement
   */
  Set<UUID> preleverAmbiguites();
}
