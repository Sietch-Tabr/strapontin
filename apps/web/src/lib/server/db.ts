import { DATABASE_URL } from "$env/static/private";
import { creerDb } from "@strapontin/core/db";

export const db = creerDb(DATABASE_URL, 20);
export const fermerDb = () => db.$client.end();
