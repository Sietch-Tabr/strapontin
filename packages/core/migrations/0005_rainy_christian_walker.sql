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
ALTER TABLE "push_abonnements" ADD CONSTRAINT "push_abonnements_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_push_abonnements_user" ON "push_abonnements" USING btree ("user_id");