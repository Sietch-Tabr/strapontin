<script lang="ts">
	import { Ticket, Search, X } from '@lucide/svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	let recherche = $state(data.recherche);
	let mois = $state(data.mois);

	function formaterDate(dateStr: string): string {
		const [annee, moisNum, jour] = dateStr.split('-').map(Number);
		return new Date(annee, moisNum - 1, jour).toLocaleDateString('fr-FR', {
			weekday: 'short',
			day: 'numeric',
			month: 'short',
			year: 'numeric'
		});
	}

	function formaterHeure(heure: string | null): string {
		if (!heure) return '';
		return heure.slice(0, 5);
	}

	function formaterPrix(min: string | null, max: string | null): string {
		if (!min) return '';
		const pMin = parseFloat(min);
		const pMax = max ? parseFloat(max) : null;
		if (pMax && pMax !== pMin) return `${pMin}–${pMax} €`;
		return `${pMin} €`;
	}

	// Mois disponibles pour le filtre (générés à partir des résultats)
	const moisDisponibles = $derived(
		[...new Set(data.tousLesConcerts.map((c) => c.date.slice(0, 7)))].sort()
	);

	function labelMois(m: string): string {
		const [annee, moisNum] = m.split('-').map(Number);
		return new Date(annee, moisNum - 1).toLocaleDateString('fr-FR', {
			month: 'long',
			year: 'numeric'
		});
	}
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Spectacles</h1>
		<p class="mt-1 text-sm text-muted-foreground">
			{data.tousLesConcerts.length} concert{data.tousLesConcerts.length !== 1 ? 's' : ''} à venir
		</p>
	</div>

	<!-- Filtres -->
	<form method="GET" class="mb-6 flex flex-wrap gap-2">
		<input
			type="text"
			name="q"
			bind:value={recherche}
			placeholder="Rechercher un artiste…"
			autocomplete="off"
			class="flex-1 min-w-40 rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
		/>
		<select
			name="mois"
			bind:value={mois}
			class="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
		>
			<option value="">Tous les mois</option>
			{#each moisDisponibles as m (m)}
				<option value={m}>{labelMois(m)}</option>
			{/each}
		</select>
		<button
			type="submit"
			class="flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
		>
			<Search size={16} />
			Filtrer
		</button>
		{#if recherche || mois}
			<a
				href="/spectacles"
				class="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground transition-colors hover:text-foreground"
			>
				<X size={16} />
				Réinitialiser
			</a>
		{/if}
	</form>

	{#if data.tousLesConcerts.length === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Ticket size={40} class="mb-4 text-muted-foreground/50" />
			{#if data.recherche || data.mois}
				<p class="mb-1 text-base font-medium text-foreground">Aucun résultat pour ces filtres.</p>
				<a href="/spectacles" class="mt-4 text-sm text-muted-foreground underline underline-offset-2">
					Voir tous les spectacles
				</a>
			{:else}
				<p class="mb-1 text-base font-medium text-foreground">Aucun spectacle à venir.</p>
				<p class="text-sm text-muted-foreground">
					Ajoutez des artistes ou des salles pour voir vos concerts.
				</p>
			{/if}
		</div>
	{:else}
		<!-- Vue tableau desktop -->
		<div class="hidden overflow-hidden rounded-xl border border-border lg:block">
			<table class="w-full text-sm">
				<thead class="border-b border-border bg-muted/40">
					<tr>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Artiste</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Date</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Heure</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Salle</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Ville</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Prix</th>
						<th class="px-4 py-3"></th>
					</tr>
				</thead>
				<tbody class="divide-y divide-border">
					{#each data.tousLesConcerts as concert (concert.id)}
						<tr class="transition-colors hover:bg-muted/30">
							<td class="px-4 py-3 font-medium text-foreground">{concert.artiste}</td>
							<td class="px-4 py-3 text-muted-foreground">{formaterDate(concert.date)}</td>
							<td class="px-4 py-3 tabular-nums text-muted-foreground">{formaterHeure(concert.heure)}</td>
							<td class="px-4 py-3 text-muted-foreground">{concert.salle ?? '—'}</td>
							<td class="px-4 py-3 text-muted-foreground">{concert.ville ?? '—'}</td>
							<td class="px-4 py-3 text-muted-foreground">{formaterPrix(concert.prixMin, concert.prixMax)}</td>
							<td class="px-4 py-3">
								{#if concert.url}
									<a
										href={concert.url}
										target="_blank"
										rel="noopener noreferrer"
										class="rounded-md border border-border px-3 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
									>
										Billets
									</a>
								{/if}
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>

		<!-- Vue cartes mobile -->
		<div class="space-y-2 lg:hidden">
			{#each data.tousLesConcerts as concert (concert.id)}
				<div class="rounded-xl border border-border bg-card px-4 py-3">
					<div class="flex items-start justify-between gap-2">
						<div class="min-w-0">
							<p class="truncate font-medium text-card-foreground">{concert.artiste}</p>
							<p class="mt-0.5 text-sm text-muted-foreground">{formaterDate(concert.date)}{concert.heure ? ` · ${formaterHeure(concert.heure)}` : ''}</p>
							<p class="mt-0.5 text-sm text-muted-foreground">
								{concert.salle}{concert.ville ? ` · ${concert.ville}` : ''}
							</p>
							{#if concert.prixMin}
								<p class="mt-0.5 text-xs text-muted-foreground">{formaterPrix(concert.prixMin, concert.prixMax)}</p>
							{/if}
						</div>
						{#if concert.url}
							<a
								href={concert.url}
								target="_blank"
								rel="noopener noreferrer"
								class="shrink-0 rounded-md border border-border px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent"
							>
								Billets
							</a>
						{/if}
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>
