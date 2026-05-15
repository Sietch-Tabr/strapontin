import { env } from "$env/dynamic/private";
import type { RequestHandler } from "@sveltejs/kit";

const JAVA_API_URL = env.JAVA_API_URL ?? "http://localhost:8080";

// Proxy catch-all : transfère toutes les requêtes /api/* vers l'API Java.
// Les cookies (SESSION) sont transmis tels quels, y compris Set-Cookie en retour.

const proxy: RequestHandler = async ({ request, params }) => {
  const url = new URL(request.url);
  const javaUrl = `${JAVA_API_URL}/api/${params.path}${url.search}`;

  const entetes = new Headers();
  const aForwarder = ["content-type", "cookie", "accept", "accept-language"];
  for (const nom of aForwarder) {
    const val = request.headers.get(nom);
    if (val) entetes.set(nom, val);
  }

  const corps =
    request.method !== "GET" && request.method !== "HEAD"
      ? await request.arrayBuffer()
      : undefined;

  // On enveloppe la réponse dans un nouveau Response avec des headers mutables
  // pour éviter "TypeError: immutable" quand SvelteKit/Vite 8 ajoute ses propres headers.
  const rep = await fetch(javaUrl, {
    method: request.method,
    headers: entetes,
    body: corps,
  });

  return new Response(rep.body, {
    status: rep.status,
    statusText: rep.statusText,
    headers: new Headers(rep.headers),
  });
};

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
