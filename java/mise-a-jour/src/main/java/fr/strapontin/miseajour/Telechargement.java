/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import java.nio.file.Path;

/**
 * Fichier téléchargé, empreinte SHA-256 (hexadécimal minuscule) calculée
 * pendant la lecture et taille réelle en octets.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public record Telechargement(Path fichier, String sha256, long taille) {}
