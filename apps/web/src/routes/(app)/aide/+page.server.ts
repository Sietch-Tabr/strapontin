import { fail } from "@sveltejs/kit";
import { resendDisponible, envoyerSignalement } from "$lib/server/email";
import type { PageServerLoad, Actions } from "./$types";

const TYPES_VALIDES = ["bug", "suggestion", "question"] as const;

export const load: PageServerLoad = async () => {
  return { resendDisponible: await resendDisponible() };
};

export const actions: Actions = {
  signalerBug: async ({ request, locals }) => {
    if (!await resendDisponible()) {
      return fail(503, { erreur: "Le service email n'est pas configuré." });
    }

    const donnees = await request.formData();
    const type = donnees.get("type")?.toString() ?? "";
    const message = donnees.get("message")?.toString().trim() ?? "";

    if (!TYPES_VALIDES.includes(type as (typeof TYPES_VALIDES)[number])) {
      return fail(400, { erreur: "Type de signalement invalide." });
    }

    if (!message) {
      return fail(400, { erreur: "Le message ne peut pas être vide." });
    }

    if (message.length > 2000) {
      return fail(400, { erreur: "Le message est trop long (2000 caractères maximum)." });
    }

    const utilisateur = locals.user!;
    await envoyerSignalement({
      type,
      message,
      expediteurEmail: utilisateur.email,
      expediteurNom: utilisateur.nom,
    });

    return { succes: true };
  },
};
