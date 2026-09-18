/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import java.util.List;

/**
 * Export complet des préférences utilisateur (artistes, salles, config).
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record ExportComplet(
    int version,
    String exporteAt,
    List<ArtisteExport> artistes,
    List<SalleExport> salles,
    ConfigExport config) {}
