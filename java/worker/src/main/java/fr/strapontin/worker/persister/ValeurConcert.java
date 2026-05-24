/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.persister;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/// Valeurs normalisées d'un concert prêtes pour l'upsert en base.
///
/// La colonne `disponible` est exclue : elle est `GENERATED ALWAYS AS`
/// dans le schéma PostgreSQL et ne doit jamais être insérée ni mise à jour.
///
/// @since 2.0.0
/// @author Kwaite
public record ValeurConcert(
    String artiste,
    String artisteNorm,
    LocalDate date,
    LocalTime heure,
    String salle,
    String salleNorm,
    String ville,
    String dept,
    BigDecimal prixMin,
    BigDecimal prixMax,
    String url,
    String source,
    String classification,
    String genre,
    String statut,
    String presaleDebut,
    String presaleNom) {}
