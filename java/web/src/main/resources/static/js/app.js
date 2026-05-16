// Strapontin v2 — JS minimal (thème, push)

// Applique le thème depuis le cookie au chargement pour éviter le FOUC
(function () {
  const theme = document.cookie.match(/(?:^|; )theme=([^;]*)/)?.[1];
  if (theme === "sombre") document.documentElement.classList.add("dark");
  else document.documentElement.classList.remove("dark");
})();
