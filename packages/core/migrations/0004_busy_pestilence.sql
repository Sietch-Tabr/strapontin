CREATE TABLE "app_config" (
	"cle" text PRIMARY KEY NOT NULL,
	"valeur_chiffree" text NOT NULL,
	"mis_a_jour_le" timestamp with time zone DEFAULT now() NOT NULL
);
