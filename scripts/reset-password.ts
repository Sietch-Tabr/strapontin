// Script de réinitialisation de mot de passe.
// Usage : DATABASE_URL=... USER_EMAIL=... NOUVEAU_MDP=... tsx scripts/reset-password.ts

import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import { eq } from "drizzle-orm";
import { accounts, users } from "@strapontin/core/schema";
import { hashPassword } from "better-auth/crypto";

const databaseUrl = process.env.DATABASE_URL;
const email = process.env.USER_EMAIL;
const nouveauMdp = process.env.NOUVEAU_MDP;

if (!databaseUrl || !email || !nouveauMdp) {
  console.error(
    "Usage : DATABASE_URL=... USER_EMAIL=... NOUVEAU_MDP=... tsx scripts/reset-password.ts",
  );
  process.exit(1);
}

const client = postgres(databaseUrl, { max: 1 });
const db = drizzle(client);

const utilisateur = await db
  .select({ id: users.id })
  .from(users)
  .where(eq(users.email, email))
  .limit(1);

if (utilisateur.length === 0) {
  console.error(`Aucun utilisateur trouvé pour l'email : ${email}`);
  await client.end();
  process.exit(1);
}

const userId = utilisateur[0].id;
const hash = await hashPassword(nouveauMdp);

await db
  .update(accounts)
  .set({ password: hash })
  .where(eq(accounts.userId, userId));

console.log(`Mot de passe mis à jour pour ${email}`);
await client.end();
