import {
  request as playwrightRequest,
  expect,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { FICHIER_AUTH_ADMIN } from "../global-setup.js";

// #949 : le pattern `playwrightRequest.newContext({ baseURL, storageState: FICHIER_AUTH_ADMIN })`
// était dupliqué une dizaine de fois entre admin.e2e.ts et admin-referentiel.e2e.ts.

export const BASE_URL =
  process.env["E2E_BASE_URL"] ?? "http://192.168.20.78:8080";

export async function creerContexteAdmin(): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: BASE_URL,
    storageState: FICHIER_AUTH_ADMIN,
  });
}

// Aucune API ne crée une salle hors suivi : la salle est créée en la suivant, puis désuivie
// (le référentiel la conserve ; purger:salles la supprime ensuite, sans concert ni suivi).
// Des noms sans racine commune sont requis : le suivi dédoublonne par similarité de nom.
export async function creerSalleReferentiel(
  ctx: APIRequestContext,
  nom: string,
): Promise<string> {
  const repSuivi = await ctx.post("/api/salles", { data: { nom } });
  expect(repSuivi.status()).toBe(201);
  const repListe = await ctx.get(
    `/api/admin/salles?nom=${encodeURIComponent(nom)}`,
  );
  expect(repListe.ok()).toBe(true);
  const salle = (
    (await repListe.json()).salles as { id: string; nom: string }[]
  ).find((s) => s.nom === nom);
  expect(salle).toBeTruthy();
  await ctx.delete(`/api/salles/${salle!.id}`);
  return salle!.id;
}

export async function supprimerUtilisateurParEmail(
  email: string,
): Promise<void> {
  const ctx = await creerContexteAdmin();
  try {
    // GET /api/admin/utilisateurs est paginé (50/page, trié par date de création croissante) —
    // un compte de test tout juste créé est donc toujours en dernière page dès que le nombre
    // total dépasse une page ; parcourt les pages jusqu'à trouver l'email plutôt que de ne
    // vérifier que la première (silencieusement inefficace, cause réelle d'une accumulation de
    // comptes de test jamais nettoyés).
    for (let page = 0; ; page++) {
      const reps = await ctx.get(`/api/admin/utilisateurs?page=${page}`);
      if (!reps.ok()) return;
      const { utilisateurs, totalPages } = await reps.json();
      const cible = (utilisateurs as { id: string; email: string }[]).find(
        (u) => u.email === email,
      );
      if (cible) {
        await ctx.delete(`/api/admin/utilisateurs/${cible.id}`);
        return;
      }
      if (page >= (totalPages ?? 1) - 1) return;
    }
  } finally {
    await ctx.dispose();
  }
}

export async function naviguerVersUtilisateurs(
  page: Page,
  isMobile: boolean,
): Promise<void> {
  if (isMobile) {
    await page.selectOption("select.param-onglets-mobile", "#utilisateurs");
  } else {
    await page.locator('a[href="#utilisateurs"]').click();
  }
  await expect(page.locator("section#utilisateurs")).toBeVisible();
}
