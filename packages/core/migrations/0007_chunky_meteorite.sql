-- Extension unaccent nécessaire pour normaliser les accents côté SQL
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Supprimer l'ancienne contrainte avant d'ajouter la colonne
ALTER TABLE "concerts" DROP CONSTRAINT "uq_concerts_artiste_date_salle";--> statement-breakpoint

-- Ajouter la colonne nullable d'abord pour pouvoir backfiller
ALTER TABLE "concerts" ADD COLUMN "salle_norm" text;--> statement-breakpoint

-- Backfill : approximation SQL de normaliserTexte (lower + unaccent + nettoyage)
UPDATE "concerts"
SET "salle_norm" = trim(
  regexp_replace(
    regexp_replace(
      lower(unaccent("salle")),
      '[^a-z0-9 ]', ' ', 'g'
    ),
    ' +', ' ', 'g'
  )
);--> statement-breakpoint

-- Supprimer les doublons révélés par la normalisation (garder le plus récent)
DELETE FROM "concerts"
WHERE id NOT IN (
  SELECT DISTINCT ON ("artiste_norm", "date", "salle_norm") id
  FROM "concerts"
  ORDER BY "artiste_norm", "date", "salle_norm", "updated_at" DESC
);--> statement-breakpoint

-- Passer en NOT NULL maintenant que toutes les lignes sont remplies
ALTER TABLE "concerts" ALTER COLUMN "salle_norm" SET NOT NULL;--> statement-breakpoint

-- Recréer la contrainte sur salle_norm normalisé
ALTER TABLE "concerts" ADD CONSTRAINT "uq_concerts_artiste_date_salle" UNIQUE("artiste_norm","date","salle_norm");
