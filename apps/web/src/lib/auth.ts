import { betterAuth } from "better-auth";
import { createAuthMiddleware } from "better-auth/api";
import { drizzleAdapter } from "better-auth/adapters/drizzle";
import { username } from "better-auth/plugins";
import pino from "pino";
import { db } from "$lib/server/db";
import * as schema from "@strapontin/core/schema";
import { envoyerResetMotDePasse } from "$lib/server/email";

const logger = pino({ name: "auth" });

export const auth = betterAuth({
  baseURL: process.env.BETTER_AUTH_URL,
  hooks: {
    after: createAuthMiddleware(async (ctx) => {
      if (ctx.path !== "/sign-in/username") return;
      const ip = ctx.request?.headers.get("x-forwarded-for") ?? "inconnu";
      const identifiant = (ctx.body as Record<string, unknown> | undefined)?.["username"] ?? "inconnu";
      if (ctx.context.newSession) {
        logger.info(
          { identifiant, ip, userId: ctx.context.newSession.user.id },
          "connexion reussie",
        );
      } else {
        logger.warn({ identifiant, ip }, "echec connexion");
      }
    }),
  },
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
    sendResetPassword: async ({ user, url }) => {
      await envoyerResetMotDePasse({ email: user.email, lienReset: url });
    },
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
  rateLimit: {
    enabled: true,
    window: 10,
    max: 100,
    customRules: {
      "/sign-in/username": {
        window: 900,
        max: 5,
      },
    },
  },
});
