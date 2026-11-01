/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.enrichissement;

import java.util.Map;
import java.util.UUID;

/**
 * Capacité optionnelle d'une source à signaler les entités pour
 * lesquelles la découverte a été explicitement abandonnée sur
 * ambiguïté (ex. homonyme humain/groupe côté {@code
 * WikidataArtisteSource}) — distingue une ambiguïté d'une non-découverte.
 * @since 2.9.0
 * @author Kwaite
 */
@FunctionalInterface
public interface SourceAvecAmbiguitesDecouverte {

  /**
   * Retourne les identifiants signalés comme ambigus depuis le dernier
   * prélèvement et remet l'ensemble à zéro.
   *
   * @return pour chaque identifiant ambigu depuis le dernier prélèvement, les
   *     éléments qui l'expliquent (candidat rejeté, écart), éventuellement vides
   */
  Map<UUID, Map<String, Object>> preleverAmbiguites();
}
