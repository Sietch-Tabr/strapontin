import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import * as schema from "./schema.js";

export type DB = ReturnType<typeof creerDb>;

export function creerDb(url: string, max = 10) {
  return drizzle(postgres(url, { max }), { schema });
}
