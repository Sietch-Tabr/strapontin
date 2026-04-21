import { auth } from "$lib/auth";
import { fail, redirect } from "@sveltejs/kit";
import type { Actions, PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ url }) => {
  const token = url.searchParams.get("token");
  if (!token) {
    redirect(302, "/forgot-password");
  }
  return { token };
};

export const actions: Actions = {
  default: async ({ request }) => {
    const donnees = await request.formData();
    const token = donnees.get("token")?.toString() ?? "";
    const motDePasse = donnees.get("motDePasse")?.toString() ?? "";
    const confirmation = donnees.get("confirmation")?.toString() ?? "";

    if (motDePasse.length < 12) {
      return fail(400, { erreur: "Le mot de passe doit contenir au moins 12 caractères." });
    }

    if (motDePasse !== confirmation) {
      return fail(400, { erreur: "Les mots de passe ne correspondent pas." });
    }

    const resultat = await auth.api
      .resetPassword({ body: { token, newPassword: motDePasse } })
      .catch(() => null);

    if (!resultat) {
      return fail(400, { erreur: "Lien invalide ou expiré. Recommencez depuis le formulaire." });
    }

    redirect(302, "/login?reset=ok");
  },
};
