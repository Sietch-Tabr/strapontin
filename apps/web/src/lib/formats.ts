export function badgeStatut(statut: string | null): { label: string; classes: string } | null {
	if (statut === 'offsale') return { label: 'Complet', classes: 'bg-orange-500/15 text-orange-600 dark:text-orange-400' };
	if (statut === 'cancelled') return { label: 'Annulé', classes: 'bg-red-500/15 text-red-600 dark:text-red-400' };
	if (statut === 'postponed' || statut === 'rescheduled') return { label: 'Reporté', classes: 'bg-yellow-500/15 text-yellow-600 dark:text-yellow-400' };
	return null;
}

export function formaterDate(dateStr: string, format: 'long' | 'court' = 'court'): string {
	const [annee, mois, jour] = dateStr.split('-').map(Number);
	return new Date(annee, mois - 1, jour).toLocaleDateString('fr-FR', {
		weekday: format === 'long' ? 'long' : 'short',
		day: 'numeric',
		month: format === 'long' ? 'long' : 'short',
		year: 'numeric'
	});
}

export function formaterHeure(heure: string | null): string {
	if (!heure) return '';
	return heure.slice(0, 5);
}
