import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

// Contrôles statiques des gabarits Vue, que le lint ne couvre pas : tableaux lisibles sur
// mobile et dialogues annoncés par leur objet aux lecteurs d'écran.

const RACINE = new URL(".", import.meta.url).pathname;

function fichiersVue(dossier) {
  return readdirSync(dossier).flatMap((nom) => {
    const chemin = join(dossier, nom);
    if (statSync(chemin).isDirectory()) return fichiersVue(chemin);
    return nom.endsWith(".vue") ? [chemin] : [];
  });
}

function gabarit(source) {
  const debut = source.indexOf("<template>");
  const fin = source.lastIndexOf("</template>");
  return debut >= 0 && fin > debut ? source.slice(debut, fin) : "";
}

const balisesOuvrantes = (html, nom) =>
  [...html.matchAll(new RegExp(`<${nom}(?=[\\s>])[^>]*>`, "g"))].map((m) => ({
    balise: m[0],
    position: m.index,
  }));

const fichiers = fichiersVue(RACINE).map((chemin) => ({
  nom: relative(RACINE, chemin),
  html: gabarit(readFileSync(chemin, "utf8")),
}));

describe("tableaux adaptés au mobile", () => {
  it("l'analyse trouve bien des tableaux", () => {
    const total = fichiers.reduce(
      (n, f) => n + balisesOuvrantes(f.html, "table").length,
      0,
    );
    expect(total).toBeGreaterThan(5);
  });

  it("chaque tableau est enveloppé dans un conteneur table-cartes", () => {
    const fautifs = fichiers.flatMap(({ nom, html }) => {
      const divs = balisesOuvrantes(html, "div");
      return balisesOuvrantes(html, "table")
        .filter(({ position }) => {
          const parent = divs.filter((d) => d.position < position).at(-1);
          return !parent?.balise.includes("table-cartes");
        })
        .map(() => nom);
    });
    expect(fautifs).toEqual([]);
  });

  it("chaque cellule porte un data-label", () => {
    const fautifs = fichiers.flatMap(({ nom, html }) =>
      balisesOuvrantes(html, "td")
        .filter(({ balise }) => !/\sdata-label=/.test(balise))
        .map(({ balise }) => `${nom} : ${balise.slice(0, 60)}`),
    );
    expect(fautifs).toEqual([]);
  });
});

describe("dialogues nommés", () => {
  it("l'analyse trouve bien des dialogues", () => {
    const total = fichiers.reduce(
      (n, f) => n + balisesOuvrantes(f.html, "dialog").length,
      0,
    );
    expect(total).toBeGreaterThan(10);
  });

  // Convention unique : titre <h2 class="dialog-titre"> relié par aria-labelledby ; aria-label
  // seulement pour un dialogue sans titre visible.
  it("chaque dialogue a un nom accessible qui pointe vers un titre existant", () => {
    const fautifs = fichiers.flatMap(({ nom, html }) =>
      balisesOuvrantes(html, "dialog")
        .filter(({ balise }) => {
          if (/\saria-label=/.test(balise)) return false;
          const reference = balise.match(/\saria-labelledby="([^"]+)"/)?.[1];
          return !reference || !html.includes(`id="${reference}"`);
        })
        .map(
          ({ balise }) =>
            `${nom} : ${balise.replace(/\s+/g, " ").slice(0, 80)}`,
        ),
    );
    expect(fautifs).toEqual([]);
  });
});
