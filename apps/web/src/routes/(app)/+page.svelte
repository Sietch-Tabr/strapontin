<script lang="ts">
	import type { PageData } from './$types';
	import { Music } from '@lucide/svelte';

	let { data }: { data: PageData } = $props();

	const optionsDate = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' } as const;

	function formaterDate(dateStr: string): string {
		const [annee, mois, jour] = dateStr.split('-').map(Number);
		return new Date(annee, mois - 1, jour).toLocaleDateString('fr-FR', optionsDate);
	}

	function formaterDateCourte(dateStr: string): string {
		const [annee, mois, jour] = dateStr.split('-').map(Number);
		return new Date(annee, mois - 1, jour).toLocaleDateString('fr-FR', {
			weekday: 'short',
			day: 'numeric',
			month: 'short'
		});
	}

	const totalConcerts = $derived(
		data.artistesGroupes.reduce((acc, g) => acc + g.concerts.length, 0)
	);
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">
			Bonjour, {data.utilisateur?.name}
		</h1>
		<p class="mt-1 text-sm text-muted-foreground">Vos prochains concerts</p>
	</div>

	{#if totalConcerts === 0}
		<div class="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-20 text-center">
			<Music size={40} class="mb-4 text-muted-foreground/50" />
			<p class="mb-1 text-base font-medium text-foreground">Aucun concert pour l'instant.</p>
			<p class="mb-6 text-sm text-muted-foreground">
				Ajoutez des artistes ou des salles pour voir vos prochains spectacles.
			</p>
			<a
				href="/artistes"
				class="rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90"
			>
				Ajouter des artistes
			</a>
		</div>
	{:else}
		<div class="space-y-4 max-w-2xl">
			{#each data.artistesGroupes as groupe (groupe.artiste)}
				<div class="rounded-xl border border-border bg-card overflow-hidden">
					<!-- En-tête artiste -->
					<div class="flex items-center gap-2 px-4 py-3 border-b border-border bg-muted/20">
						<p class="font-semibold text-card-foreground">{groupe.artiste}</p>
						{#if groupe.estTournee}
							<span class="rounded-full bg-foreground/10 px-2 py-0.5 text-xs font-medium text-foreground">
								Tournée · {groupe.concerts.length} dates
							</span>
						{/if}
					</div>

					<!-- Liste des dates — lien billetterie sur chaque ligne -->
					<div class="divide-y divide-border/50">
						{#each groupe.concerts as concert (concert.id)}
							<div class="flex items-center justify-between px-4 py-3">
								<div class="min-w-0">
									{#if groupe.estTournee}
										<p class="text-sm font-medium text-foreground">{formaterDateCourte(concert.date)}</p>
									{:else}
										<p class="text-sm text-muted-foreground">{formaterDate(concert.date)}</p>
									{/if}
									<p class="mt-0.5 text-xs text-muted-foreground truncate">
										{concert.salle}{concert.ville ? ` · ${concert.ville}` : ''}
									</p>
								</div>
								{#if concert.url}
									<a
										href={concert.url}
										target="_blank"
										rel="noopener noreferrer"
										class="ml-3 shrink-0 rounded-md border border-border px-3 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
									>
										Billets
									</a>
								{/if}
							</div>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>
