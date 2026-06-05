const ANNEE_COURANTE = new Date().getFullYear();

const OPTS_COURT = { weekday: "short", day: "numeric", month: "short" };
const OPTS_COURT_ANNEE = {
  weekday: "short",
  day: "numeric",
  month: "short",
  year: "numeric",
};
const OPTS_LONG = {
  weekday: "long",
  day: "numeric",
  month: "long",
  year: "numeric",
};

function parseDateLocale(dateIso) {
  const [y, m, d] = dateIso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

export function formaterDate(dateIso, longue = false) {
  if (!dateIso) return "";
  try {
    const date = parseDateLocale(dateIso);
    const opts = longue
      ? OPTS_LONG
      : date.getFullYear() !== ANNEE_COURANTE
        ? OPTS_COURT_ANNEE
        : OPTS_COURT;
    return date.toLocaleDateString("fr-FR", opts);
  } catch {
    return dateIso;
  }
}

export function formaterAvecHeure(dateIso, heureStr) {
  if (!dateIso) return "";
  try {
    const datePart = formaterDate(dateIso);
    if (!heureStr) return datePart;
    const [h, m] = heureStr.split(":").map(Number);
    return (
      datePart + " " + h + "h" + (m === 0 ? "" : String(m).padStart(2, "0"))
    );
  } catch {
    return dateIso;
  }
}

export function formaterInstant(instantIso) {
  if (!instantIso) return "";
  try {
    const d = new Date(instantIso);
    const dateStr = new Intl.DateTimeFormat("fr-FR", {
      timeZone: "Europe/Paris",
      weekday: "short",
      day: "numeric",
      month: "short",
    }).format(d);
    const timeStr = new Intl.DateTimeFormat("fr-FR", {
      timeZone: "Europe/Paris",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(d);
    const [h, m] = timeStr.split(":");
    return dateStr + " " + h + "h" + m;
  } catch {
    return instantIso;
  }
}
