import {
  boolean,
  char,
  date,
  index,
  integer,
  jsonb,
  numeric,
  pgTable,
  text,
  time,
  timestamp,
  uuid,
} from "drizzle-orm/pg-core";

// -------------------------------------------------------------------
// concerts
// Source de vérité pour tous les concerts, toutes sources confondues et dédoublonnés.
// -------------------------------------------------------------------

export const concerts = pgTable(
  "concerts",
  {
    id: uuid("id").primaryKey().defaultRandom(),
    artiste: text("artiste").notNull(),
    artisteNorm: text("artiste_norm").notNull(),
    date: date("date").notNull(),
    heure: time("heure"),
    salle: text("salle").notNull(),
    ville: text("ville"),
    dept: char("dept", { length: 3 }),
    prixMin: numeric("prix_min", { precision: 8, scale: 2 }),
    prixMax: numeric("prix_max", { precision: 8, scale: 2 }),
    url: text("url"),
    source: text("source").notNull(),
    disponible: boolean("disponible").notNull().default(true),
    firstSeen: timestamp("first_seen", { withTimezone: true })
      .notNull()
      .defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true })
      .notNull()
      .defaultNow(),
  },
  (table) => [
    index("idx_concerts_artiste_norm").on(table.artisteNorm),
    index("idx_concerts_date").on(table.date),
    index("idx_concerts_dept").on(table.dept),
    index("idx_concerts_disponible").on(table.disponible),
  ],
);

// -------------------------------------------------------------------
// users
// -------------------------------------------------------------------

export const users = pgTable("users", {
  id: uuid("id").primaryKey().defaultRandom(),
  email: text("email").notNull().unique(),
  passwordHash: text("password_hash").notNull(),
  role: text("role").notNull().default("user"),
  createdAt: timestamp("created_at", { withTimezone: true })
    .notNull()
    .defaultNow(),
});

// -------------------------------------------------------------------
// user_artistes
// -------------------------------------------------------------------

export const userArtistes = pgTable(
  "user_artistes",
  {
    userId: uuid("user_id")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    artisteNormalise: text("artiste_normalise").notNull(),
    artisteAffiche: text("artiste_affiche").notNull(),
    createdAt: timestamp("created_at", { withTimezone: true })
      .notNull()
      .defaultNow(),
  },
  (table) => [index("idx_user_artistes_norm").on(table.artisteNormalise)],
);

// -------------------------------------------------------------------
// user_salles
// -------------------------------------------------------------------

export const userSalles = pgTable("user_salles", {
  userId: uuid("user_id")
    .notNull()
    .references(() => users.id, { onDelete: "cascade" }),
  salleId: text("salle_id").notNull(),
});

// -------------------------------------------------------------------
// user_config
// -------------------------------------------------------------------

export const userConfig = pgTable("user_config", {
  userId: uuid("user_id")
    .primaryKey()
    .references(() => users.id, { onDelete: "cascade" }),
  departements: text("departements").array().notNull().default([]),
  rayonKm: integer("rayon_km").notNull().default(50),
  filtreDisponibles: boolean("filtre_disponibles").notNull().default(true),
  searchPeriodMonths: integer("search_period_months").notNull().default(6),
  notificationsEmail: boolean("notifications_email").notNull().default(false),
  updatedAt: timestamp("updated_at", { withTimezone: true })
    .notNull()
    .defaultNow(),
});

// -------------------------------------------------------------------
// user_matches
// Index de matching : quels concerts correspondent à quels users.
// Mis à jour par le worker après chaque fetch.
// -------------------------------------------------------------------

export const userMatches = pgTable(
  "user_matches",
  {
    userId: uuid("user_id")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    concertId: uuid("concert_id")
      .notNull()
      .references(() => concerts.id, { onDelete: "cascade" }),
    notifieLe: timestamp("notifie_le", { withTimezone: true }),
    vuLe: timestamp("vu_le", { withTimezone: true }),
  },
  (table) => [
    index("idx_user_matches_user").on(table.userId),
    index("idx_user_matches_nouveaux").on(table.userId),
  ],
);

// -------------------------------------------------------------------
// fetch_jobs
// Historique et planning des jobs de fetch. Lu par la page admin.
// -------------------------------------------------------------------

export const fetchJobs = pgTable(
  "fetch_jobs",
  {
    id: uuid("id").primaryKey().defaultRandom(),
    type: text("type").notNull(),
    params: jsonb("params").notNull(),
    status: text("status").notNull().default("pending"),
    lastRun: timestamp("last_run", { withTimezone: true }),
    nextRun: timestamp("next_run", { withTimezone: true }),
    durationMs: integer("duration_ms"),
    error: text("error"),
    createdAt: timestamp("created_at", { withTimezone: true })
      .notNull()
      .defaultNow(),
  },
  (table) => [
    index("idx_fetch_jobs_next_run").on(table.nextRun),
    index("idx_fetch_jobs_status").on(table.status),
  ],
);
