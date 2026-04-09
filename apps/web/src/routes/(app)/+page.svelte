<script lang="ts">
	import type { PageData } from './$types';
	import { Music } from '@lucide/svelte';

	let { data }: { data: PageData } = $props();

	const optionsDate = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' } as const;

	function formaterDate(dateStr: string): string {
		const [annee, mois, jour] = dateStr.split('-').map(Number);
		return new Date(annee, mois - 1, jour).toLocaleDateString('fr-FR', optionsDate);
	}

	let vueTableau = $state(false);

	$effect(() => {
		const sauvegarde = localStorage.getItem('vue-accueil');
		if (sauvegarde === 'tableau') vueTableau = true;
	});

	function basculerVue() {
		vueTableau = !vueTableau;
		localStorage.setItem('vue-accueil', vueTableau ? 'tableau' : 'cartes');
	}
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8 flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-semibold text-foreground">
				Bonjour, {data.utilisateur?.name}
			</h1>
			<p class="mt-1 text-sm text-muted-foreground">Vos prochains concerts</p>
		</div>

		{#if data.prochainsConcerts.length > 0}
			<button
				onclick={basculerVue}
				class="hidden rounded-md border border-border px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground lg:block"
			>
				{vueTableau ? 'Vue cartes' : 'Vue tableau'}
			</button>
		{/if}
	</div>

	{#if data.prochainsConcerts.length === 0}
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
	{:else if vueTableau}
		<div class="hidden overflow-hidden rounded-xl border border-border lg:block">
			<table class="w-full text-sm">
				<thead class="border-b border-border bg-muted/40">
					<tr>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Artiste</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Date</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Salle</th>
						<th class="px-4 py-3 text-left font-medium text-muted-foreground">Ville</th>
					</tr>
				</thead>
				<tbody class="divide-y divide-border">
					{#each data.prochainsConcerts as concert (concert.id)}
						<tr class="transition-colors hover:bg-muted/30">
							<td class="px-4 py-3 font-medium text-foreground">{concert.artiste}</td>
							<td class="px-4 py-3 text-muted-foreground">{formaterDate(concert.date)}</td>
							<td class="px-4 py-3 text-muted-foreground">{concert.salle ?? '—'}</td>
							<td class="px-4 py-3 text-muted-foreground">{concert.ville ?? '—'}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
		<!-- fallback cartes sur mobile même si toggle tableau actif -->
		<div class="grid gap-3 lg:hidden sm:grid-cols-2">
			{#each data.prochainsConcerts as concert (concert.id)}
				<div class="rounded-xl border border-border bg-card p-4">
					<p class="font-semibold text-card-foreground">{concert.artiste}</p>
					<p class="mt-1 text-sm text-muted-foreground">{formaterDate(concert.date)}</p>
					{#if concert.salle}
						<p class="mt-0.5 text-sm text-muted-foreground">{concert.salle}</p>
					{/if}
					{#if concert.ville}
						<p class="mt-0.5 text-xs text-muted-foreground/70">{concert.ville}</p>
					{/if}
				</div>
			{/each}
		</div>
	{:else}
		<div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
			{#each data.prochainsConcerts as concert (concert.id)}
				<div class="rounded-xl border border-border bg-card p-4 transition-colors hover:bg-card/80">
					<p class="font-semibold text-card-foreground">{concert.artiste}</p>
					<p class="mt-1 text-sm text-muted-foreground">{formaterDate(concert.date)}</p>
					{#if concert.salle}
						<p class="mt-0.5 text-sm text-muted-foreground">{concert.salle}</p>
					{/if}
					{#if concert.ville}
						<p class="mt-0.5 text-xs text-muted-foreground/70">{concert.ville}</p>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>
