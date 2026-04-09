<script lang="ts">
	import { CalendarDays } from '@lucide/svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	function formaterDate(dateStr: string): string {
		const [annee, mois, jour] = dateStr.split('-').map(Number);
		return new Date(annee, mois - 1, jour).toLocaleDateString('fr-FR', {
			weekday: 'long',
			day: 'numeric',
			month: 'long'
		});
	}

	function formaterHeure(heure: string | null): string {
		if (!heure) return '';
		return heure.slice(0, 5);
	}

	// Regrouper par date
	const parDate = $derived(
		data.prochainsConcerts.reduce(
			(acc, concert) => {
				const cle = concert.date;
				if (!acc[cle]) acc[cle] = [];
				acc[cle].push(concert);
				return acc;
			},
			{} as Record<string, typeof data.prochainsConcerts>
		)
	);

	const dates = $derived(Object.keys(parDate).sort());

	function estAujourdhui(dateStr: string): boolean {
		return dateStr === data.aujourdhui;
	}

	function estDemain(dateStr: string): boolean {
		const demain = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().split('T')[0];
		return dateStr === demain;
	}

	function labelDate(dateStr: string): string {
		if (estAujourdhui(dateStr)) return 'Aujourd\'hui';
		if (estDemain(dateStr)) return 'Demain';
		return formaterDate(dateStr);
	}
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Quinzaine</h1>
		<p class="mt-1 text-sm text-muted-foreground">
			{data.prochainsConcerts.length} concert{data.prochainsConcerts.length !== 1 ? 's' : ''} dans les 15 prochains jours
		</p>
	</div>

	{#if data.prochainsConcerts.length === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<CalendarDays size={40} class="mb-4 text-muted-foreground/50" />
			<p class="mb-1 text-base font-medium text-foreground">Aucun concert dans les 15 prochains jours.</p>
			<p class="text-sm text-muted-foreground">
				Ajoutez des artistes ou des salles pour voir apparaître vos concerts ici.
			</p>
		</div>
	{:else}
		<div class="space-y-8">
			{#each dates as date (date)}
				<section>
					<h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground capitalize">
						{labelDate(date)}
						{#if estAujourdhui(date)}
							<span class="rounded-full bg-foreground px-2 py-0.5 text-xs text-background">
								aujourd'hui
							</span>
						{/if}
					</h2>

					<div class="space-y-2">
						{#each parDate[date] as concert (concert.id)}
							<div class="flex items-center gap-4 rounded-xl border border-border bg-card px-4 py-3">
								{#if concert.heure}
									<span class="w-12 shrink-0 text-sm font-medium tabular-nums text-muted-foreground">
										{formaterHeure(concert.heure)}
									</span>
								{/if}
								<div class="min-w-0 flex-1">
									<p class="truncate font-medium text-card-foreground">
										{concert.artiste}
										{#if concert.estNew}
											<span class="ml-1 rounded-full bg-blue-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-blue-600 dark:text-blue-400">NEW</span>
										{/if}
									</p>
									<p class="truncate text-sm text-muted-foreground">
										{concert.salle}{concert.ville ? ` · ${concert.ville}` : ''}
									</p>
								</div>
								{#if concert.url}
									<a
										href={concert.url}
										target="_blank"
										rel="noopener noreferrer"
										class="shrink-0 rounded-md border border-border px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
									>
										Billets
									</a>
								{/if}
							</div>
						{/each}
					</div>
				</section>
			{/each}
		</div>
	{/if}
</div>
