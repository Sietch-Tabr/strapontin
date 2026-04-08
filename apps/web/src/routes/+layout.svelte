<script lang="ts">
	import '../app.css';
	import favicon from '$lib/assets/favicon.svg';

	let { children, data } = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

<html lang="fr" class={data.theme === 'dark' ? 'dark' : ''}>
	<body>
		{@render children()}
	</body>
</html>
