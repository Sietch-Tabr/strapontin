import { db } from "$lib/server/db";
import { redis } from "$lib/server/redis";
import { sql } from "drizzle-orm";
import type { RequestHandler } from "./$types";

export const GET: RequestHandler = async () => {
  try {
    await db.execute(sql`SELECT 1`);
    await redis.ping();
    return new Response(JSON.stringify({ status: "ok" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  } catch {
    return new Response(JSON.stringify({ status: "error" }), {
      status: 503,
      headers: { "Content-Type": "application/json" },
    });
  }
};
