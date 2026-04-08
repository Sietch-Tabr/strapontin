CREATE TABLE "concerts" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"artiste" text NOT NULL,
	"artiste_norm" text NOT NULL,
	"date" date NOT NULL,
	"heure" time,
	"salle" text NOT NULL,
	"ville" text,
	"dept" char(3),
	"prix_min" numeric(8, 2),
	"prix_max" numeric(8, 2),
	"url" text,
	"source" text NOT NULL,
	"disponible" boolean DEFAULT true NOT NULL,
	"first_seen" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
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
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "user_artistes" (
	"user_id" uuid NOT NULL,
	"artiste_normalise" text NOT NULL,
	"artiste_affiche" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "user_config" (
	"user_id" uuid PRIMARY KEY NOT NULL,
	"departements" text[] DEFAULT '{}' NOT NULL,
	"rayon_km" integer DEFAULT 50 NOT NULL,
	"filtre_disponibles" boolean DEFAULT true NOT NULL,
	"search_period_months" integer DEFAULT 6 NOT NULL,
	"notifications_email" boolean DEFAULT false NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "user_matches" (
	"user_id" uuid NOT NULL,
	"concert_id" uuid NOT NULL,
	"notifie_le" timestamp with time zone,
	"vu_le" timestamp with time zone
);
--> statement-breakpoint
CREATE TABLE "user_salles" (
	"user_id" uuid NOT NULL,
	"salle_id" text NOT NULL
);
--> statement-breakpoint
CREATE TABLE "users" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"email" text NOT NULL,
	"password_hash" text NOT NULL,
	"role" text DEFAULT 'user' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "users_email_unique" UNIQUE("email")
);
--> statement-breakpoint
ALTER TABLE "user_artistes" ADD CONSTRAINT "user_artistes_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_config" ADD CONSTRAINT "user_config_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_matches" ADD CONSTRAINT "user_matches_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_matches" ADD CONSTRAINT "user_matches_concert_id_concerts_id_fk" FOREIGN KEY ("concert_id") REFERENCES "public"."concerts"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_salles" ADD CONSTRAINT "user_salles_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_concerts_artiste_norm" ON "concerts" USING btree ("artiste_norm");--> statement-breakpoint
CREATE INDEX "idx_concerts_date" ON "concerts" USING btree ("date");--> statement-breakpoint
CREATE INDEX "idx_concerts_dept" ON "concerts" USING btree ("dept");--> statement-breakpoint
CREATE INDEX "idx_concerts_disponible" ON "concerts" USING btree ("disponible");--> statement-breakpoint
CREATE INDEX "idx_fetch_jobs_next_run" ON "fetch_jobs" USING btree ("next_run");--> statement-breakpoint
CREATE INDEX "idx_fetch_jobs_status" ON "fetch_jobs" USING btree ("status");--> statement-breakpoint
CREATE INDEX "idx_user_artistes_norm" ON "user_artistes" USING btree ("artiste_normalise");--> statement-breakpoint
CREATE INDEX "idx_user_matches_user" ON "user_matches" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "idx_user_matches_nouveaux" ON "user_matches" USING btree ("user_id");