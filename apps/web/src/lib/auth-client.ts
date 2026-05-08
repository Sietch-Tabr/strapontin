import { createAuthClient } from "better-auth/svelte";
import { usernameClient, twoFactorClient } from "better-auth/client/plugins";

export const authClient = createAuthClient({
  plugins: [usernameClient(), twoFactorClient()],
});

export const { signIn, signOut, useSession } = authClient;
