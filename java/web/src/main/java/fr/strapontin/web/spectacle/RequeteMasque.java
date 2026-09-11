/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

/**
 * Corps JSON de la requête PATCH /api/spectacles/matches/{concertId}.
 *
 * @since 2.1.0
 * @author Kwaite
 */
public record RequeteMasque(boolean masquer) {}
