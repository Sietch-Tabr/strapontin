/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.time.LocalDate;
import java.util.List;

/**
 * Concerts d'une région et dernière date que la source garantit avoir
 * entièrement couverte ({@code horizon}, null sans garantie).
 * @since 3.0.1
 * @author Kwaite
 */
public record ResultatRegion(List<ConcertBrut> concerts, LocalDate horizon) {}
