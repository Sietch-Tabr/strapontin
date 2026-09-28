/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import fr.strapontin.core.ConcertBrut;
import java.time.LocalDate;
import java.util.List;

/**
 * Concerts d'une région et date jusqu'à laquelle la source garantit les avoir
 * tous renvoyés : un concert non revu au-delà de cet horizon n'est pas absent,
 * seulement hors de portée de la requête (plafond de pagination, fenêtre).
 *
 * @param concerts concerts trouvés
 * @param horizon dernière date entièrement couverte, ou {@code null} si la
 *     source ne garantit aucune couverture complète
 * @since 3.0.1
 * @author Kwaite
 */
public record ResultatRegion(List<ConcertBrut> concerts, LocalDate horizon) {}
