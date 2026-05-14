<script lang="ts">
	import { page } from '$app/stores';
</script>

<svelte:head>
	<title>Erreur — Strapontin</title>
</svelte:head>

<div class="flex min-h-screen flex-col items-center justify-center bg-background p-6 text-center">
	<p class="mb-2 text-4xl font-bold text-muted-foreground">{$page.status}</p>
	<h1 class="mb-4 text-xl font-semibold text-foreground">
		{$page.status === 404 ? 'Page introuvable' : 'Une erreur est survenue'}
	</h1>
	<p class="mb-6 text-sm text-muted-foreground">
		{$page.error?.message ?? 'Erreur inattendue'}
	</p>
	<a
		href="/"
		class="rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90"
	>
		Retour à l'accueil
	</a>
</div>
