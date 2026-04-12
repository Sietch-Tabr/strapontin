import { betterAuth } from "better-auth";
import { drizzleAdapter } from "better-auth/adapters/drizzle";
import { username } from "better-auth/plugins";
import { PUBLIC_BASE_URL } from "$env/static/public";
import { db } from "$lib/server/db";
import * as schema from "@strapontin/core/schema";

export const auth = betterAuth({
  baseURL: PUBLIC_BASE_URL,
  database: drizzleAdapter(db, {
    provider: "pg",
    schema: {
      user: schema.users,
      session: schema.sessions,
      account: schema.accounts,
      verification: schema.verifications,
    },
  }),
  emailAndPassword: {
    enabled: true,
  },
  plugins: [
    username({
      minUsernameLength: 3,
      maxUsernameLength: 30,
    }),
  ],
  user: {
    additionalFields: {
      role: {
        type: "string",
        defaultValue: "user",
      },
      mustChangeCredentials: {
        type: "boolean",
        defaultValue: false,
      },
    },
  },
});
