import { JAVA_API_URL, entetesJava } from "$lib/server/api-java";
import { fail, redirect } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ locals }) => {
  if (!locals.user?.mustChangeCredentials) {
    redirect(302, "/");
  }
  return {};
};

export const actions: Actions = {
  default: async ({ request, cookies, locals }) => {
    if (!locals.user?.mustChangeCredentials) {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const identifiant =
      donnees.get("identifiant")?.toString().trim().toLowerCase() ?? "";
    const motDePasseActuel = donnees.get("motDePasseActuel")?.toString() ?? "";
    const motDePasseNouveau =
      donnees.get("motDePasseNouveau")?.toString() ?? "";
    const motDePasseConfirmation =
      donnees.get("motDePasseConfirmation")?.toString() ?? "";

    if (!nom || !identifiant || !motDePasseActuel || !motDePasseNouveau) {
      return fail(400, { erreur: "Tous les champs sont obligatoires." });
    }
    if (motDePasseNouveau.length < 12) {
      return fail(400, {
        erreur: "Le mot de passe doit contenir au moins 12 caractères.",
      });
    }
    if (motDePasseNouveau !== motDePasseConfirmation) {
      return fail(400, { erreur: "Les mots de passe ne correspondent pas." });
    }

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/first-login`, {
      method: "POST",
      headers: entetesJava(session),
      body: JSON.stringify({
        nom,
        identifiant,
        motDePasseActuel,
        motDePasseNouveau,
      }),
    });

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, { erreur: corps.erreur ?? "Erreur inattendue." });
    }

    // Java a invalidé la session — rediriger vers login
    cookies.delete("SESSION", { path: "/" });
    redirect(302, "/login");
  },
};
