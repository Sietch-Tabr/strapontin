import { appelApi } from "./utils/api.js";
import "./styles/base.css";
import "./styles/layout.css";
import "./styles/components.css";
import "./styles/pages.css";
import { createApp } from "vue";
import App from "./App.vue";
import router from "./router/index.js";
import { afficherToast } from "./composables/useToasts.js";
import {
  construireCorpsErreurJs,
  construireCorpsErreurPromise,
} from "./utils/erreursClient.js";

createApp(App).use(router).mount("#app");

// Exposition globale après le montage (environnement browser garanti),
// pour que le service worker (sw.js) puisse remonter ses erreurs.
window.afficherToast = afficherToast;

// remonte les erreurs JS non catchées et les rejections de promesse vers
// POST /api/client/erreur (endpoint jusque-là jamais câblé côté front, cf. ClientController).
function signalerErreurClient(corps) {
  appelApi("/api/client/erreur", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(corps),
  }).catch(() => {});
}

window.addEventListener("error", (e) => {
  signalerErreurClient(
    construireCorpsErreurJs(e.message, e.filename, e.lineno, e.colno, e.error),
  );
});

window.addEventListener("unhandledrejection", (e) => {
  signalerErreurClient(
    construireCorpsErreurPromise(e.reason, window.location.href),
  );
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});

  // un second listener "nav-ancre" existait dans App.vue (traitement effectif, toujours
  // router.push) — celui-ci faisait double emploi et sa branche scrollIntoView était du code mort
  // (les URLs construites côté serveur utilisent toujours une query string, jamais un #fragment,
  // donc cible.hash était toujours vide). Ne garder qu'un seul listener message, dans App.vue.
  navigator.serviceWorker.addEventListener("message", (e) => {
    if (e.data?.type === "sw-error") {
      window.afficherToast?.(
        "Erreur service worker : " + e.data.message,
        "erreur",
      );
    }
  });
}
