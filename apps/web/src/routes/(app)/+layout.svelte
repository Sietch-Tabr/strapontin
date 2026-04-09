<script lang="ts">
	import Sidebar from '$lib/components/navigation/Sidebar.svelte';
	import BottomNav from '$lib/components/navigation/BottomNav.svelte';
	import type { LayoutData } from './$types';

	let { data, children }: { data: LayoutData; children: import('svelte').Snippet } = $props();
</script>

<div class="flex h-screen bg-background">
	<Sidebar utilisateur={data.utilisateur} />

	<main class="flex-1 overflow-auto pb-16 lg:pb-0">
		{@render children()}
	</main>

	<BottomNav />
</div>
