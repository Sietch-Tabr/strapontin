/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import java.util.List;

/// Corps JSON de la requête POST /api/decouverte/fetcher.
///
/// @since 2.1.0
/// @author Kwaite
public record RequeteFetch(List<String> depts) {}
