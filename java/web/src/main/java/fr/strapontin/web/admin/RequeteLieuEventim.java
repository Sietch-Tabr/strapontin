/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import jakarta.validation.constraints.Size;

/**
 * Corps JSON d'une requête portant le nom exact d'un lieu Eventim (vide pour
 * retirer le lieu configuré).
 *
 * @param libelle nom du lieu tel qu'Eventim l'affiche
 * @since 3.0.1
 * @author Kwaite
 */
public record RequeteLieuEventim(
    @Size(max = 200, message = "Lieu Eventim invalide.") String libelle) {}
