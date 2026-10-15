import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

// Contrôle automatique de la charte graphique (.claude/rules/frontend-vue.md, section Charte) :
// un rôle d'action correspond à un seul style, les éléments de la charte ne sont définis que dans
// les feuilles partagées, et aucune classe partagée ne reste sans usage.

const RACINE = new URL(".", import.meta.url).pathname;

const ROLES_BOUTON = [
  "bouton-primaire",
  "bouton-secondaire",
  "bouton-texte",
  "bouton-danger",
  "bouton-bascule",
];
const MODIFICATEURS_BOUTON = ["bouton-petit", "bouton-pleine-largeur"];
const ETATS = ["actif"];
// Éléments de navigation (barre du bas) : ils suivent le style des liens de navigation.
const NAVIGATION = ["nav-bas-plus"];
const PREFIXES_CHARTE = ["bouton", "badge", "alerte"];
const FEUILLE_CHARTE = "styles/components.css";
// Placer un élément de la charte ne change pas son aspect.
const PROPRIETES_DE_PLACEMENT =
  /^(position|top|right|bottom|left|inset|z-index|display|visibility|order|align-self|justify-self|flex(-grow|-shrink|-basis)?|grid(-[a-z]+)*|margin(-[a-z]+)*|width|min-width|max-width)$/;
const VERBES_DESTRUCTEURS = /^(Supprimer|Fusionner|Révoquer|Effacer)\b/;

function tousLesFichiers(dossier) {
  return readdirSync(dossier).flatMap((nom) => {
    const chemin = join(dossier, nom);
    return statSync(chemin).isDirectory() ? tousLesFichiers(chemin) : [chemin];
  });
}

const sources = tousLesFichiers(RACINE)
  .filter((f) => /\.(vue|js|css)$/.test(f) && !f.endsWith(".test.js"))
  .map((chemin) => ({
    nom: relative(RACINE, chemin),
    texte: readFileSync(chemin, "utf8"),
  }));

const sansCommentaires = (css) => css.replace(/\/\*[\s\S]*?\*\//g, "");

function feuillesDe({ nom, texte }) {
  if (nom.endsWith(".css")) return [sansCommentaires(texte)];
  return [...texte.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) =>
    sansCommentaires(m[1]),
  );
}

// Règles les plus internes (celles d'un @media comprises), avec leurs propriétés.
function regles(css) {
  return [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .map((m) => ({
      selecteur: m[1].trim(),
      proprietes: m[2]
        .split(";")
        .map((d) => d.split(":")[0].trim())
        .filter(Boolean),
    }))
    .filter(
      (r) =>
        !r.selecteur.startsWith("@") && !/^(from|to|\d+%)/.test(r.selecteur),
    );
}

const classesDuSelecteur = (selecteur) =>
  [...selecteur.replace(/\[[^\]]*\]/g, "").matchAll(/\.([a-zA-Z][\w-]*)/g)].map(
    (m) => m[1],
  );

const toutesLesRegles = sources.flatMap((source) =>
  feuillesDe(source).flatMap((css) =>
    regles(css).map((r) => ({ ...r, fichier: source.nom })),
  ),
);

// Boutons et liens stylés en bouton : balise ouvrante (valeurs pouvant contenir « > »),
// classes statiques et dynamiques, texte.
function boutons(nom, texte) {
  const debut = texte.indexOf("<template>");
  const fin = texte.lastIndexOf("</template>");
  if (debut < 0) return [];
  const html = texte.slice(debut, fin);
  const resultat = [];
  for (const ouverture of html.matchAll(/<(button|a|RouterLink)(?=[\s>])/g)) {
    const balise = ouverture[1];
    let i = ouverture.index + balise.length + 1;
    let guillemet = null;
    while (i < html.length && (guillemet || html[i] !== ">")) {
      if (html[i] === '"' || html[i] === "'") {
        guillemet = guillemet === html[i] ? null : (guillemet ?? html[i]);
      }
      i++;
    }
    const attributs = html.slice(ouverture.index, i);
    const statiques = (attributs.match(/\sclass="([^"]*)"/)?.[1] ?? "")
      .split(/\s+/)
      .filter(Boolean);
    if (balise !== "button" && !statiques.some((c) => c.startsWith("bouton")))
      continue;
    const fermeture = html.indexOf(`</${balise}>`, i);
    const contenu = html
      .slice(i + 1, fermeture)
      .replace(/<[^>]+>|\{\{[\s\S]*?\}\}/g, " ")
      .replace(/\s+/g, " ")
      .trim();
    const expression = attributs.match(/\s:class="([^"]*)"/)?.[1] ?? "";
    const dynamiques = [
      ...[...expression.matchAll(/'([\w-]+)'/g)].map((m) => m[1]),
      ...[...expression.matchAll(/([\w-]+)\s*:/g)].map((m) => m[1]),
    ];
    resultat.push({ nom, balise: attributs, contenu, statiques, dynamiques });
  }
  return resultat;
}

const tousLesBoutons = sources
  .filter((s) => s.nom.endsWith(".vue"))
  .flatMap((s) => boutons(s.nom, s.texte));

const libelle = (b) =>
  `${b.nom} : « ${b.contenu.slice(0, 40)} » [${b.statiques.join(" ")}]`;

describe("charte : boutons", () => {
  it("l'analyse trouve bien des boutons", () => {
    expect(tousLesBoutons.length).toBeGreaterThan(100);
  });

  it("chaque bouton porte exactement un rôle de la charte", () => {
    const fautifs = tousLesBoutons
      .filter((b) => {
        if (b.statiques.some((c) => NAVIGATION.includes(c))) return false;
        if (b.statiques.includes("bouton-icone")) {
          return b.statiques.some(
            (c) => c.startsWith("bouton") && c !== "bouton-icone",
          );
        }
        const roles = b.statiques.filter((c) => ROLES_BOUTON.includes(c));
        return !b.statiques.includes("bouton") || roles.length !== 1;
      })
      .map(libelle);
    expect(fautifs).toEqual([]);
  });

  it("un bouton ne prend aucune autre classe de bouton, et seule la classe d'état varie", () => {
    const connues = [
      "bouton",
      "bouton-icone",
      ...ROLES_BOUTON,
      ...MODIFICATEURS_BOUTON,
    ];
    const fautifs = tousLesBoutons
      .filter(
        (b) =>
          b.statiques.some(
            (c) => c.startsWith("bouton") && !connues.includes(c),
          ) || b.dynamiques.some((c) => !ETATS.includes(c)),
      )
      .map(libelle);
    expect(fautifs).toEqual([]);
  });

  it("une classe propre à un bouton ne fait que le placer", () => {
    const propres = new Set(
      tousLesBoutons.flatMap((b) =>
        b.statiques.filter(
          (c) =>
            !c.startsWith("bouton") &&
            !NAVIGATION.includes(c) &&
            !ETATS.includes(c),
        ),
      ),
    );
    const fautifs = toutesLesRegles
      .filter((r) =>
        classesDuSelecteur(r.selecteur).some((c) => propres.has(c)),
      )
      .flatMap((r) =>
        r.proprietes
          .filter((p) => !PROPRIETES_DE_PLACEMENT.test(p))
          .map((p) => `${r.fichier} : ${r.selecteur} { ${p} }`),
      );
    expect(fautifs).toEqual([]);
  });

  it("un même libellé garde le même rôle partout", () => {
    const attendus = [
      [/^Annuler$/, "bouton-texte"],
      [/^Enregistrer$/, "bouton-primaire"],
      [/^Fermer$/, "bouton-secondaire"],
    ];
    const fautifs = tousLesBoutons
      .filter((b) =>
        attendus.some(
          ([motif, role]) =>
            motif.test(b.contenu) && !b.statiques.includes(role),
        ),
      )
      .map(libelle);
    expect(fautifs).toEqual([]);
  });

  it("une action destructive est toujours un bouton danger", () => {
    const fautifs = tousLesBoutons
      .filter(
        (b) =>
          VERBES_DESTRUCTEURS.test(b.contenu) &&
          !b.statiques.includes("bouton-danger"),
      )
      .map(libelle);
    expect(fautifs).toEqual([]);
  });

  it("dans un dialogue, l'action vient en premier, en principal ou en danger", () => {
    const fautifs = sources
      .filter((s) => s.nom.endsWith(".vue"))
      .flatMap((s) =>
        [
          ...s.texte.matchAll(/class="dialog-pied"[^>]*>([\s\S]*?)<\/div>/g),
        ].flatMap((m) => {
          const pied = boutons(s.nom, `<template>${m[1]}</template>`);
          if (pied.length < 2 || !pied.some((b) => b.contenu === "Annuler"))
            return [];
          const premier = pied[0];
          const ok =
            premier.statiques.includes("bouton-primaire") ||
            premier.statiques.includes("bouton-danger");
          return ok ? [] : [libelle(premier)];
        }),
      );
    expect(fautifs).toEqual([]);
  });
});

describe("charte : distinctions sémantiques", () => {
  const feuille = sansCommentaires(
    sources.find((s) => s.nom === FEUILLE_CHARTE).texte,
  );
  const fond = (niveau) =>
    feuille.match(
      new RegExp(
        `\\.bouton-bascule\\[data-niveau="${niveau}"\\]\\.actif\\s*\\{[^}]*background:\\s*([^;]+);`,
      ),
    )?.[1];

  it("chaque filtre de niveau actif a sa couleur propre", () => {
    const fonds = ["info", "warn", "error"].map(fond);
    expect(fonds.every(Boolean)).toBe(true);
    expect(new Set(fonds).size).toBe(3);
  });

  it("chaque bouton bascule portant un niveau a une règle d'état actif", () => {
    const niveaux = new Set(
      sources
        .filter((s) => s.nom.endsWith(".vue"))
        .flatMap((s) => [
          ...s.texte.matchAll(/<button[^>]*\sdata-niveau="([\w-]+)"/g),
        ])
        .map((m) => m[1]),
    );
    const sansRegle = [...niveaux].filter((n) => !fond(n));
    expect(sansRegle).toEqual([]);
  });

  it("les filtres de niveau d'Admin > Logs portent leur niveau", () => {
    const logs = sources.find((s) => s.nom.endsWith("admin/AdminLogs.vue"));
    expect(logs.texte).toMatch(
      /bouton-bascule[^>]*\s:data-niveau="niv"|:data-niveau="niv"[^>]*bouton-bascule/,
    );
  });
});

describe("charte : éléments définis une seule fois", () => {
  // Boutons dans la feuille de la charte, badges et alertes dans les feuilles partagées.
  it("bouton, badge et alerte ne sont stylés que dans les feuilles partagées", () => {
    const fautifs = toutesLesRegles
      .filter((r) =>
        classesDuSelecteur(r.selecteur).some((c) =>
          PREFIXES_CHARTE.some(
            (p) =>
              (c === p || c.startsWith(`${p}-`)) &&
              (p === "bouton"
                ? r.fichier !== FEUILLE_CHARTE
                : !r.fichier.startsWith("styles/")),
          ),
        ),
      )
      .flatMap((r) =>
        r.proprietes
          .filter((p) => !PROPRIETES_DE_PLACEMENT.test(p))
          .map((p) => `${r.fichier} : ${r.selecteur} { ${p} }`),
      );
    expect(fautifs).toEqual([]);
  });
});

// Contrôle de l'aspect : tout ce qui change l'apparence d'un élément (couleur, ombre, icône,
// taille de police, espacement, rayon) vient des variables de base.css, jamais d'une valeur en dur.
const FEUILLE_THEME = "styles/base.css";
const PROPRIETE_DE_DIMENSION =
  /^(font-size|margin(-[a-z]+)*|padding(-[a-z]+)*|gap|row-gap|column-gap|border(-[a-z]+)*-radius)$/;
const DIMENSION_LITTERALE = /-?\d*\.?\d+(px|rem|em)\b/;
const COULEUR_LITTERALE =
  /#[0-9a-fA-F]{3,8}\b|%23[0-9a-fA-F]{3,8}|\b(?:rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color-mix)\(|(?:^|[\s,(])(?:white|black|red|green|blue|gray|grey|orange|yellow|purple)(?![\w-])/i;

// Découpe un bloc de déclarations sans couper dans une parenthèse ou une chaîne.
function separerDeclarations(corps) {
  const resultat = [];
  let courant = "";
  let profondeur = 0;
  let guillemet = null;
  for (const c of corps) {
    if (guillemet) {
      if (c === guillemet) guillemet = null;
    } else if (c === '"' || c === "'") guillemet = c;
    else if (c === "(") profondeur++;
    else if (c === ")") profondeur--;
    else if (c === ";" && profondeur === 0) {
      resultat.push(courant);
      courant = "";
      continue;
    }
    courant += c;
  }
  resultat.push(courant);
  return resultat
    .map((d) => d.trim())
    .filter(Boolean)
    .map((d) => {
      const i = d.indexOf(":");
      return { propriete: d.slice(0, i).trim(), valeur: d.slice(i + 1).trim() };
    })
    .filter((d) => d.propriete && d.valeur);
}

// Toutes les déclarations des feuilles de l'application, y compris celles des étapes d'une
// animation (@keyframes). La page hors ligne de sw.js, autonome par construction, n'en fait pas partie.
const toutesLesDeclarations = sources
  .filter((s) => /\.(css|vue)$/.test(s.nom))
  .flatMap((source) =>
    feuillesDe(source).flatMap((css) =>
      [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
        .filter((m) => !m[1].trim().startsWith("@"))
        .flatMap((m) =>
          separerDeclarations(m[2]).map((d) => ({
            ...d,
            selecteur: m[1].trim(),
            fichier: source.nom,
          })),
        ),
    ),
  );

const enClair = (d) =>
  `${d.fichier} : ${d.selecteur} { ${d.propriete}: ${d.valeur} }`;

describe("charte : aspect porté par les variables du thème", () => {
  it("l'analyse trouve bien des déclarations", () => {
    expect(toutesLesDeclarations.length).toBeGreaterThan(1000);
  });

  it("aucune couleur n'est écrite en dur hors des variables de base.css", () => {
    const fautifs = toutesLesDeclarations
      .filter(
        (d) =>
          !(d.fichier === FEUILLE_THEME && d.propriete.startsWith("--")) &&
          COULEUR_LITTERALE.test(d.valeur),
      )
      .map(enClair);
    expect(fautifs).toEqual([]);
  });

  it("le thème sombre ne redéfinit que des variables", () => {
    const fautifs = toutesLesDeclarations
      .filter(
        (d) =>
          /(^|[^\w-])\.sombre(?![\w-])/.test(d.selecteur) &&
          !(d.fichier === FEUILLE_THEME && d.propriete.startsWith("--")),
      )
      .map(enClair);
    expect(fautifs).toEqual([]);
  });

  it("taille de police, espacement et rayon passent par l'échelle de variables", () => {
    const fautifs = toutesLesDeclarations
      .filter(
        (d) =>
          PROPRIETE_DE_DIMENSION.test(d.propriete) &&
          DIMENSION_LITTERALE.test(
            d.valeur.replace(/(var|env)\((?:[^()]|\([^()]*\))*\)/g, ""),
          ),
      )
      .map(enClair);
    expect(fautifs).toEqual([]);
  });

  it("toute variable utilisée est définie", () => {
    const definies = new Set(
      sources.flatMap((s) =>
        [...s.texte.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]),
      ),
    );
    // Variables posées par le code (style en ligne, setProperty) : leur nom figure hors des feuilles.
    sources
      .filter((s) => !s.nom.endsWith(".css"))
      .forEach((s) =>
        [...s.texte.matchAll(/['"`](--[\w-]+)['"`]|(--[\w-]+)\s*:/g)].forEach(
          (m) => definies.add(m[1] ?? m[2]),
        ),
      );
    const fautifs = toutesLesDeclarations
      .flatMap((d) =>
        [...d.valeur.matchAll(/var\(\s*(--[\w-]+)/g)].map((m) => ({
          d,
          nom: m[1],
        })),
      )
      .filter(({ nom }) => !definies.has(nom))
      .map(({ d, nom }) => `${nom} dans ${enClair(d)}`);
    expect(fautifs).toEqual([]);
  });

  it("une variable utilisée n'a pas de valeur de repli", () => {
    const fautifs = toutesLesDeclarations
      .filter((d) => /var\(\s*--[\w-]+\s*,/.test(d.valeur))
      .map(enClair);
    expect(fautifs).toEqual([]);
  });
});

describe("charte : classes partagées utilisées", () => {
  const code = sources
    .filter((s) => !s.nom.endsWith(".css"))
    .map((s) => s.texte.replace(/<style[\s\S]*?<\/style>/g, ""))
    .concat(readFileSync(join(RACINE, "../index.html"), "utf8"))
    .join("\n");
  const present = (mot) =>
    new RegExp(`(^|[^\\w-])${mot}([^\\w-]|$)`).test(code);

  // Classe directe, nom de transition, ou classe construite par concaténation
  // (« 'badge-source-' + source.type »).
  function utilisee(classe) {
    if (present(classe)) return true;
    const transition = classe.match(/^(.+)-(enter|leave)-(active|from|to)$/);
    if (transition) return code.includes(`name="${transition[1]}"`);
    return [...classe.matchAll(/-/g)].some(({ index }) => {
      const prefixe = classe.slice(0, index + 1);
      const concatene = new RegExp(`['"\`]${prefixe}['"\`]\\s*\\+`).test(code);
      return concatene && present(classe.slice(index + 1));
    });
  }

  it("aucune classe d'une feuille partagée n'est orpheline", () => {
    const definies = new Set(
      toutesLesRegles
        .filter((r) => r.fichier.startsWith("styles/"))
        .flatMap((r) => classesDuSelecteur(r.selecteur)),
    );
    const orphelines = [...definies].filter((c) => !utilisee(c)).sort();
    expect(orphelines).toEqual([]);
  });
});
