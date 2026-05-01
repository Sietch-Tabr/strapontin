import { creerDb } from "@strapontin/core/db";
import {
  concerts,
  users,
  userArtistes,
  userConfig,
  userSalles,
} from "@strapontin/core/schema";
import { eq } from "drizzle-orm";

const url = process.env["DATABASE_URL"];
if (!url) throw new Error("DATABASE_URL manquante pour les tests d'intégration");

export const dbTest = creerDb(url);

export async function nettoyerConcerts() {
  await dbTest.delete(concerts);
}

export async function nettoyerUser(id: string) {
  await dbTest.delete(userArtistes).where(eq(userArtistes.userId, id));
  await dbTest.delete(userSalles).where(eq(userSalles.userId, id));
  await dbTest.delete(userConfig).where(eq(userConfig.userId, id));
  await dbTest.delete(users).where(eq(users.id, id));
}

export { users, userArtistes, userConfig, userSalles, concerts };
