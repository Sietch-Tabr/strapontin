import { auth } from "$lib/auth";
import { fail } from "@sveltejs/kit";
import type { Actions } from "./$types";

export const actions: Actions = {
  default: async ({ request }) => {
    const donnees = await request.formData();
    const email = donnees.get("email")?.toString().trim() ?? "";

    if (!email) {
      return fail(400, { erreur: "L'email est obligatoire." });
    }

    // Better-Auth envoie le lien de reset par email — on ne distingue pas si l'email
    // existe en base pour éviter l'énumération de comptes.
    await auth.api.requestPasswordReset({ body: { email, redirectTo: "/reset-password" } }).catch(() => null);

    return { succes: true };
  },
};
