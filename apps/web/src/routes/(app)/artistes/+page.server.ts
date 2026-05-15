import { JAVA_API_URL, entetesJava } from "$lib/server/api-java";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ cookies, url }) => {
  const session = cookies.get("SESSION");
  const q = (url.searchParams.get("q") ?? "").trim().slice(0, 100);

  const reponse = await fetch(
    `${JAVA_API_URL}/api/artistes?q=${encodeURIComponent(q)}`,
    { headers: entetesJava(session) },
  );

  if (!reponse.ok) {
    return { artistes: [], suggestions: [], dejaLeSuivi: false, recherche: q };
  }

  const donnees = (await reponse.json()) as {
    artistes: {
      artisteNormalise: string;
      artisteAffiche: string;
      createdAt: string;
    }[];
    suggestions: { nom: string; source: string }[];
    dejaLeSuivi: boolean;
  };

  return {
    artistes: donnees.artistes ?? [],
    suggestions: donnees.suggestions ?? [],
    dejaLeSuivi: donnees.dejaLeSuivi ?? false,
    recherche: q,
  };
};

export const actions: Actions = {
  ajouter: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/artistes`, {
      method: "POST",
      headers: entetesJava(session),
      body: JSON.stringify({ nom }),
    });

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, { erreur: corps.erreur ?? "Erreur inattendue." });
    }

    return { succes: true };
  },

  supprimer: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const artisteNormalise = donnees.get("artisteNormalise")?.toString() ?? "";

    if (!artisteNormalise)
      return fail(400, { erreur: "Identifiant manquant." });

    const session = cookies.get("SESSION");
    await fetch(
      `${JAVA_API_URL}/api/artistes/${encodeURIComponent(artisteNormalise)}`,
      { method: "DELETE", headers: entetesJava(session) },
    );

    return { succes: true };
  },
};
