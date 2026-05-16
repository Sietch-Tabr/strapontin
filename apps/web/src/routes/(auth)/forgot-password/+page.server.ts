import { JAVA_API_URL } from "$lib/server/api-java";
import { fail } from "@sveltejs/kit";
import type { Actions } from "./$types";

export const actions: Actions = {
  default: async ({ request }) => {
    const donnees = await request.formData();
    const email = donnees.get("email")?.toString().trim() ?? "";

    if (!email) {
      return fail(400, { erreur: "L'email est obligatoire." });
    }

    // Java envoie le lien de reset par email — on ne distingue pas si l'email
    // existe en base pour éviter l'énumération de comptes.
    await fetch(`${JAVA_API_URL}/api/auth/forgot-password`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email }),
    }).catch(() => null);

    return { succes: true };
  },
};
