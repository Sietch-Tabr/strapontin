import { creerDb } from "@strapontin/core/db";

const url = process.env["DATABASE_URL"];
if (!url) throw new Error("DATABASE_URL manquante");

export const db = creerDb(url, 20);
