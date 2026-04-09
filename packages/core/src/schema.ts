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
  unique,
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
    unique("uq_concerts_artiste_date_salle").on(
      table.artisteNorm,
      table.date,
      table.salle,
    ),
    index("idx_concerts_artiste_norm").on(table.artisteNorm),
    index("idx_concerts_date").on(table.date),
    index("idx_concerts_dept").on(table.dept),
    index("idx_concerts_disponible").on(table.disponible),
  ],
);

// -------------------------------------------------------------------
// users
// Table principale des utilisateurs — colonnes requises par Better Auth
// + champ additionnel role (user | admin).
// -------------------------------------------------------------------

export const users = pgTable("users", {
  id: text("id").primaryKey(),
  name: text("name").notNull(),
  email: text("email").notNull().unique(),
  emailVerified: boolean("email_verified").notNull().default(false),
  image: text("image"),
  role: text("role").notNull().default("user"),
  createdAt: timestamp("created_at", { withTimezone: true })
    .notNull()
    .defaultNow(),
  updatedAt: timestamp("updated_at", { withTimezone: true })
    .notNull()
    .defaultNow(),
});

// -------------------------------------------------------------------
// sessions
// Sessions Better Auth.
// -------------------------------------------------------------------

export const sessions = pgTable("sessions", {
  id: text("id").primaryKey(),
  expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
  token: text("token").notNull().unique(),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull(),
  updatedAt: timestamp("updated_at", { withTimezone: true }).notNull(),
  ipAddress: text("ip_address"),
  userAgent: text("user_agent"),
  userId: text("user_id")
    .notNull()
    .references(() => users.id, { onDelete: "cascade" }),
});

// -------------------------------------------------------------------
// accounts
// Comptes d'authentification Better Auth (stocke le hash du mot de passe).
// -------------------------------------------------------------------

export const accounts = pgTable("accounts", {
  id: text("id").primaryKey(),
  accountId: text("account_id").notNull(),
  providerId: text("provider_id").notNull(),
  userId: text("user_id")
    .notNull()
    .references(() => users.id, { onDelete: "cascade" }),
  accessToken: text("access_token"),
  refreshToken: text("refresh_token"),
  idToken: text("id_token"),
  accessTokenExpiresAt: timestamp("access_token_expires_at", {
    withTimezone: true,
  }),
  refreshTokenExpiresAt: timestamp("refresh_token_expires_at", {
    withTimezone: true,
  }),
  scope: text("scope"),
  password: text("password"),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull(),
  updatedAt: timestamp("updated_at", { withTimezone: true }).notNull(),
});

// -------------------------------------------------------------------
// verifications
// Tokens de vérification Better Auth (email, mot de passe oublié…).
// -------------------------------------------------------------------

export const verifications = pgTable("verifications", {
  id: text("id").primaryKey(),
  identifier: text("identifier").notNull(),
  value: text("value").notNull(),
  expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
  createdAt: timestamp("created_at", { withTimezone: true }),
  updatedAt: timestamp("updated_at", { withTimezone: true }),
});

// -------------------------------------------------------------------
// user_artistes
// -------------------------------------------------------------------

export const userArtistes = pgTable(
  "user_artistes",
  {
    userId: text("user_id")
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
  userId: text("user_id")
    .notNull()
    .references(() => users.id, { onDelete: "cascade" }),
  salleId: text("salle_id").notNull(),
  // ID Ticketmaster de la salle (null si non résolu ou salle saisie librement)
  tmVenueId: text("tm_venue_id"),
});

// -------------------------------------------------------------------
// user_config
// -------------------------------------------------------------------

export const userConfig = pgTable("user_config", {
  userId: text("user_id")
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
    userId: text("user_id")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    concertId: uuid("concert_id")
      .notNull()
      .references(() => concerts.id, { onDelete: "cascade" }),
    notifieLe: timestamp("notifie_le", { withTimezone: true }),
    vuLe: timestamp("vu_le", { withTimezone: true }),
  },
  (table) => [
    unique("uq_user_matches_user_concert").on(table.userId, table.concertId),
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
