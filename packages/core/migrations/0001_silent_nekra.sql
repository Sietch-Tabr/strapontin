-- 1. Supprimer les contraintes FK avant changement de type
ALTER TABLE "user_artistes" DROP CONSTRAINT "user_artistes_user_id_users_id_fk";
--> statement-breakpoint
ALTER TABLE "user_config" DROP CONSTRAINT "user_config_user_id_users_id_fk";
--> statement-breakpoint
ALTER TABLE "user_matches" DROP CONSTRAINT "user_matches_user_id_users_id_fk";
--> statement-breakpoint
ALTER TABLE "user_salles" DROP CONSTRAINT "user_salles_user_id_users_id_fk";
--> statement-breakpoint

-- 2. Modifier users.id en premier (c'est lui qui est référencé)
ALTER TABLE "users" ALTER COLUMN "id" SET DATA TYPE text USING id::text;
--> statement-breakpoint
ALTER TABLE "users" ALTER COLUMN "id" DROP DEFAULT;
--> statement-breakpoint

-- 3. Modifier les colonnes user_id dans les tables liées
ALTER TABLE "user_artistes" ALTER COLUMN "user_id" SET DATA TYPE text USING user_id::text;
--> statement-breakpoint
ALTER TABLE "user_config" ALTER COLUMN "user_id" SET DATA TYPE text USING user_id::text;
--> statement-breakpoint
ALTER TABLE "user_matches" ALTER COLUMN "user_id" SET DATA TYPE text USING user_id::text;
--> statement-breakpoint
ALTER TABLE "user_salles" ALTER COLUMN "user_id" SET DATA TYPE text USING user_id::text;
--> statement-breakpoint

-- 4. Nouvelles colonnes sur users
ALTER TABLE "users" ADD COLUMN "name" text NOT NULL DEFAULT '';
--> statement-breakpoint
ALTER TABLE "users" ALTER COLUMN "name" DROP DEFAULT;
--> statement-breakpoint
ALTER TABLE "users" ADD COLUMN "email_verified" boolean NOT NULL DEFAULT false;
--> statement-breakpoint
ALTER TABLE "users" ADD COLUMN "image" text;
--> statement-breakpoint
ALTER TABLE "users" ADD COLUMN "updated_at" timestamp with time zone NOT NULL DEFAULT now();
--> statement-breakpoint

-- 5. Supprimer password_hash
ALTER TABLE "users" DROP COLUMN "password_hash";
--> statement-breakpoint

-- 6. Recréer les contraintes FK sur les tables existantes
ALTER TABLE "user_artistes" ADD CONSTRAINT "user_artistes_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_config" ADD CONSTRAINT "user_config_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_matches" ADD CONSTRAINT "user_matches_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "user_salles" ADD CONSTRAINT "user_salles_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint

-- 7. Nouvelles tables Better Auth
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
CREATE TABLE "verifications" (
	"id" text PRIMARY KEY NOT NULL,
	"identifier" text NOT NULL,
	"value" text NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"created_at" timestamp with time zone,
	"updated_at" timestamp with time zone
);
--> statement-breakpoint

-- 8. FK pour les nouvelles tables Better Auth
ALTER TABLE "accounts" ADD CONSTRAINT "accounts_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
