/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.miseajour;

import fr.strapontin.exploitation.EtatMiseAJour;

/**
 * Ce que l'onglet « Mise à jour » affiche : version en service, version
 * proposée ({@code null} s'il n'y en a pas), dernier état du module, demande
 * en attente, mise à jour en cours et TOTP activé (condition de l'installation).
 *
 * @since 3.2.0
 * @author Kwaite
 */
public record EtatMiseAJourWeb(
    String versionInstallee,
    String versionProposee,
    EtatMiseAJour etat,
    boolean demandeEnAttente,
    boolean enCours,
    boolean totpActif) {}
