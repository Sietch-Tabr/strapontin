// Parsing JSON tolérant au corps vide, partagé entre useBanniere.js et AdminBanniere.vue
// (GET /api/banniere/json renvoie un corps vide, Content-Length: 0, quand aucune bannière
// n'est publiée — response.json() lèverait une SyntaxError sur un corps vide, jamais une
// réponse JSON `null` explicite).

export function parseJsonOuNull(texte) {
  return texte ? JSON.parse(texte) : null;
}
