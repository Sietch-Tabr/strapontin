CREATE EXTENSION IF NOT EXISTS unaccent;
--> statement-breakpoint
CREATE TABLE "accounts" (
	"id" text PRIMARY KEY NOT NULL,
	"account_id" text NOT NULL,
	"provider_id" text NOT NULL,
	"user_id" text NOT NULL,
	"access_token" text,
	"refresh_token" text,
	"id_token" text,
	"access_token_expires_at" timestamp with time zone,
	"refresh_token_expires_at" timestamp with time zone,
	"scope" text,
	"password" text,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL
);
--> statement-breakpoint
CREATE TABLE "app_config" (
	"cle" text PRIMARY KEY NOT NULL,
	"valeur_chiffree" text NOT NULL,
	"mis_a_jour_le" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "concerts" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"artiste" text NOT NULL,
	"artiste_norm" text NOT NULL,
	"date" date NOT NULL,
	"heure" time,
	"salle" text NOT NULL,
	"salle_norm" text NOT NULL,
	"ville" text,
	"dept" varchar(3),
	"prix_min" numeric(8, 2),
	"prix_max" numeric(8, 2),
	"url" text,
	"source" text NOT NULL,
	"classification" text,
	"genre" text,
	"disponible" boolean DEFAULT true NOT NULL,
	"first_seen" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "uq_concerts_artiste_date_salle" UNIQUE("artiste_norm","date","salle_norm"),
	CONSTRAINT "chk_concerts_source" CHECK ("concerts"."source" IN ('ticketmaster', 'eventim', 'bandsintown'))
);
--> statement-breakpoint
CREATE TABLE "fetch_jobs" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"type" text NOT NULL,
	"params" jsonb NOT NULL,
	"status" text DEFAULT 'pending' NOT NULL,
	"last_run" timestamp with time zone,
	"next_run" timestamp with time zone,
	"duration_ms" integer,
	"error" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "chk_fetch_jobs_status" CHECK ("fetch_jobs"."status" IN ('pending', 'running', 'done', 'error'))
);
--> statement-breakpoint
CREATE TABLE "push_abonnements" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"endpoint" text NOT NULL,
	"p256dh" text NOT NULL,
	"auth" text NOT NULL,
	"cree_le" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "uq_push_user_endpoint" UNIQUE("user_id","endpoint")
);
--> statement-breakpoint
CREATE TABLE "sessions" (
	"id" text PRIMARY KEY NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"token" text NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	"ip_address" text,
	"user_agent" text,
	"user_id" text NOT NULL,
	CONSTRAINT "sessions_token_unique" UNIQUE("token")
);
--> statement-breakpoint
CREATE TABLE "user_artistes" (
	"user_id" text NOT NULL,
	"artiste_normalise" text NOT NULL,
	"artiste_affiche" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "uq_user_artistes_user_artiste" UNIQUE("user_id","artiste_normalise")
);
--> statement-breakpoint
CREATE TABLE "user_config" (
	"user_id" text PRIMARY KEY NOT NULL,
	"departements" text[] DEFAULT '{}' NOT NULL,
	"rayon_km" integer DEFAULT 50 NOT NULL,
	"filtre_disponibles" boolean DEFAULT true NOT NULL,
	"frequence_notifications" text NOT NULL DEFAULT 'jamais',
	"notif_artistes" boolean DEFAULT true NOT NULL,
	"notif_salles" boolean DEFAULT false NOT NULL,
	"theme" text DEFAULT 'auto' NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "chk_frequence_notifications" CHECK ("user_config"."frequence_notifications" IN ('jamais', 'temps_reel', 'quotidien')),
	CONSTRAINT "chk_user_config_theme" CHECK ("user_config"."theme" IN ('auto', 'clair', 'sombre'))
);
--> statement-breakpoint
CREATE TABLE "user_matches" (
	"user_id" text NOT NULL,
	"concert_id" uuid NOT NULL,
	"notifie_le" timestamp with time zone,
	"vu_le" timestamp with time zone,
	"masque_le" timestamp with time zone,
	CONSTRAINT "uq_user_matches_user_concert" UNIQUE("user_id","concert_id")
);
--> statement-breakpoint
CREATE TABLE "user_salles" (
	"user_id" text NOT NULL,
	"salle_id" text NOT NULL,
	"tm_venue_id" text,
	CONSTRAINT "user_salles_user_id_salle_id_pk" PRIMARY KEY("user_id","salle_id")
);
--> statement-breakpoint
CREATE TABLE "users" (
	"id" text PRIMARY KEY NOT NULL,
	"name" text NOT NULL,
	"email" text NOT NULL,
	"email_verified" boolean DEFAULT false NOT NULL,
	"image" text,
	"role" text DEFAULT 'user' NOT NULL,
	"username" text,
	"display_username" text,
	"must_change_credentials" boolean DEFAULT false NOT NULL,
	"created_at" timestamp with time zone NOT NULL,
	"updated_at" timestamp with time zone NOT NULL,
	CONSTRAINT "users_email_unique" UNIQUE("email"),
	CONSTRAINT "users_username_unique" UNIQUE("username"),
	CONSTRAINT "chk_users_role" CHECK ("users"."role" IN ('user', 'admin'))
);
--> statement-breakpoint
CREATE TABLE "verifications" (
	"id" text PRIMARY KEY NOT NULL,
	"identifier" text NOT NULL,
	"value" text NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"created_at" timestamp with time zone,
	"updated_at" timestamp with time zone
);
--> statement-breakpoint
ALTER TABLE "accounts" ADD CONSTRAINT "accounts_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "push_abonnements" ADD CONSTRAINT "push_abonnements_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_artistes" ADD CONSTRAINT "user_artistes_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_config" ADD CONSTRAINT "user_config_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_matches" ADD CONSTRAINT "user_matches_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_matches" ADD CONSTRAINT "user_matches_concert_id_concerts_id_fk" FOREIGN KEY ("concert_id") REFERENCES "public"."concerts"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_salles" ADD CONSTRAINT "user_salles_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
CREATE INDEX "idx_concerts_artiste_norm" ON "concerts" USING btree ("artiste_norm");
--> statement-breakpoint
CREATE INDEX "idx_concerts_date" ON "concerts" USING btree ("date");
--> statement-breakpoint
CREATE INDEX "idx_concerts_dept" ON "concerts" USING btree ("dept");
--> statement-breakpoint
CREATE INDEX "idx_concerts_disponible" ON "concerts" USING btree ("disponible");
--> statement-breakpoint
CREATE INDEX "idx_fetch_jobs_next_run" ON "fetch_jobs" USING btree ("next_run");
--> statement-breakpoint
CREATE INDEX "idx_fetch_jobs_status" ON "fetch_jobs" USING btree ("status");
--> statement-breakpoint
CREATE INDEX "idx_push_abonnements_user" ON "push_abonnements" USING btree ("user_id");
--> statement-breakpoint
CREATE INDEX "idx_user_artistes_norm" ON "user_artistes" USING btree ("artiste_normalise");
--> statement-breakpoint
CREATE INDEX "idx_user_matches_user" ON "user_matches" USING btree ("user_id");
--> statement-breakpoint
CREATE INDEX "idx_user_matches_non_vus" ON "user_matches" USING btree ("user_id") WHERE "user_matches"."vu_le" IS NULL;
