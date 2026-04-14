DROP INDEX "idx_user_matches_nouveaux";--> statement-breakpoint
ALTER TABLE "user_salles" ADD CONSTRAINT "user_salles_user_id_salle_id_pk" PRIMARY KEY("user_id","salle_id");--> statement-breakpoint
CREATE INDEX "idx_user_matches_non_vus" ON "user_matches" USING btree ("user_id") WHERE "user_matches"."vu_le" IS NULL;--> statement-breakpoint
ALTER TABLE "user_artistes" ADD CONSTRAINT "uq_user_artistes_user_artiste" UNIQUE("user_id","artiste_normalise");--> statement-breakpoint
ALTER TABLE "concerts" ADD CONSTRAINT "chk_concerts_source" CHECK ("concerts"."source" IN ('ticketmaster', 'eventim', 'bandsintown'));--> statement-breakpoint
ALTER TABLE "fetch_jobs" ADD CONSTRAINT "chk_fetch_jobs_status" CHECK ("fetch_jobs"."status" IN ('pending', 'running', 'done', 'error'));--> statement-breakpoint
ALTER TABLE "users" ADD CONSTRAINT "chk_users_role" CHECK ("users"."role" IN ('user', 'admin'));