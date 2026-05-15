import { JAVA_API_URL, entetesJava } from "$lib/server/api-java";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ cookies, url }) => {
  const session = cookies.get("SESSION");
  const q = (url.searchParams.get("q") ?? "").trim().slice(0, 100);

  const reponse = await fetch(
    `${JAVA_API_URL}/api/salles?q=${encodeURIComponent(q)}`,
    { headers: entetesJava(session) },
  );

  if (!reponse.ok) {
    return {
      sallesSuivies: [],
      resultatsRecherche: [],
      recherche: q,
      dejaLaSuivie: false,
    };
  }

  const donnees = (await reponse.json()) as {
    salles: {
      salleId: string;
      nom: string;
      tmVenueId: string | null;
      createdAt: string;
    }[];
    resultats: {
      salle: string;
      ville: string | null;
      tmVenueId: string | null;
      source: string;
    }[];
    dejaLaSuivie: boolean;
  };

  return {
    sallesSuivies: (donnees.salles ?? []).map((s) => ({ salleId: s.salleId })),
    resultatsRecherche: (donnees.resultats ?? []).map((r) => ({
      salle: r.salle,
      ville: r.ville,
      tmVenueId: r.tmVenueId,
      source: r.source as "base" | "ticketmaster",
    })),
    recherche: q,
    dejaLaSuivie: donnees.dejaLaSuivie ?? false,
  };
};

export const actions: Actions = {
  ajouter: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const salleId = donnees.get("salleId")?.toString().trim() ?? "";
    const tmVenueId = donnees.get("tmVenueId")?.toString().trim() || null;

    if (!salleId)
      return fail(400, { erreur: "Identifiant de salle invalide." });

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/salles`, {
      method: "POST",
      headers: entetesJava(session),
      body: JSON.stringify({ nom: salleId, tmVenueId }),
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
    const salleId = donnees.get("salleId")?.toString() ?? "";

    if (!salleId) return fail(400, { erreur: "Identifiant manquant." });

    const session = cookies.get("SESSION");
    await fetch(`${JAVA_API_URL}/api/salles/${encodeURIComponent(salleId)}`, {
      method: "DELETE",
      headers: entetesJava(session),
    });

    return { succes: true };
  },
};
