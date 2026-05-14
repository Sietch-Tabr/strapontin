<script lang="ts">
	import { page } from '$app/stores';
</script>

<svelte:head>
	<title>{$page.status === 404 ? 'Page introuvable' : 'Erreur'} — Strapontin</title>
</svelte:head>

<div class="flex flex-col items-center justify-center p-16 text-center">
	<p class="mb-2 text-5xl font-bold text-muted-foreground">{$page.status}</p>
	<h1 class="mb-3 text-xl font-semibold text-foreground">
		{$page.status === 404 ? 'Page introuvable' : 'Erreur serveur'}
	</h1>
	<p class="mb-6 text-sm text-muted-foreground">
		{$page.error?.message ?? 'Une erreur inattendue est survenue.'}
	</p>
	<a
		href="/"
		class="rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
	>
		Retour à l'accueil
	</a>
</div>
