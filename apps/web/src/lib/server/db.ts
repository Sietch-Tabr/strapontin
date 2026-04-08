import { DATABASE_URL } from "$env/static/private";
import { creerDb } from "@concert-web/core/db";

export const db = creerDb(DATABASE_URL);
