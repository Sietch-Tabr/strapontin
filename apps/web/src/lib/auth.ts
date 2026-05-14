import { betterAuth } from "better-auth";
import { createAuthMiddleware } from "better-auth/api";
import { drizzleAdapter } from "better-auth/adapters/drizzle";
import { username, twoFactor } from "better-auth/plugins";
import pino from "pino";
import { db } from "$lib/server/db";
import * as schema from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { envoyerInvitation, envoyerResetMotDePasse } from "$lib/server/email";
import { logAdmin } from "$lib/server/log-admin";

const logger = pino({ name: "auth" });

export const auth = betterAuth({
  baseURL: process.env.BETTER_AUTH_URL,
  hooks: {
    after: createAuthMiddleware(async (ctx) => {
      if (ctx.path !== "/sign-in/username") return;
      const ip = ctx.request?.headers.get("x-forwarded-for") ?? "inconnu";
      const identifiant =
        (ctx.body as Record<string, unknown> | undefined)?.["username"] ??
        "inconnu";
      if (ctx.context.newSession) {
        const userId = ctx.context.newSession.user.id;
        logger.info({ identifiant, ip, userId }, "connexion reussie");
        void logAdmin("info", "auth", "connexion réussie", {
          identifiant,
          ip,
          userId,
        });
      } else {
        logger.warn({ identifiant, ip }, "echec connexion");
        void logAdmin("warn", "auth", "échec connexion", { identifiant, ip });
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
      twoFactor: schema.twoFactors,
    },
  }),
  emailAndPassword: {
    enabled: true,
    sendResetPassword: async ({ user, url }) => {
      const [userDb] = await db
        .select({
          mustChangeCredentials: schema.users.mustChangeCredentials,
          username: schema.users.username,
        })
        .from(schema.users)
        .where(eq(schema.users.email, user.email));
      if (userDb?.mustChangeCredentials) {
        await envoyerInvitation({
          email: user.email,
          identifiant: userDb.username ?? "",
          lienReset: url,
        });
      } else {
        await envoyerResetMotDePasse({ email: user.email, lienReset: url });
      }
    },
  },
  plugins: [
    username({
      minUsernameLength: 3,
      maxUsernameLength: 30,
    }),
    twoFactor({
      issuer: "Strapontin",
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
      "/two-factor/verify-totp": {
        window: 900,
        max: 5,
      },
    },
  },
});
