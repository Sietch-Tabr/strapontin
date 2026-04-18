<script lang="ts">
	import { History } from '@lucide/svelte';
</script>

<svelte:head>
	<title>Nouveautés — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Nouveautés</h1>
	</div>

	<div class="max-w-xl space-y-10">

		<section>
			<div class="mb-4 flex items-center gap-2 text-foreground">
				<History size={20} aria-hidden="true" />
				<h2 class="text-base font-medium">v 1.0 — Avril 2026</h2>
			</div>
			<div class="space-y-6 text-sm text-muted-foreground">
				<p>Version initiale.</p>
			</div>
		</section>

	</div>
</div>
