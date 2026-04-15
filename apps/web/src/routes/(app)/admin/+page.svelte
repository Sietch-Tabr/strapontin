<script lang="ts">
	import { enhance } from '$app/forms';
	import { UserPlus, Trash2, ShieldCheck, Zap } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	let emailNouvelUser = $state('');
	let creationEnCours = $state(false);
	let confirmationSupprimer = $state<string | null>(null);

	function formaterReset(timestampMs: number): string {
		if (!timestampMs) return '—';
		return new Date(timestampMs).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
	}

	function formaterMisAJour(iso: string): string {
		return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
	}
</script>

<svelte:head>
	<title>Administration — Strapontin</title>
</svelte:head>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Administration</h1>
		<p class="mt-1 text-sm text-muted-foreground">Gestion des comptes utilisateurs.</p>
	</div>

	<div class="max-w-2xl space-y-10">

		<!-- Quota Ticketmaster -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground flex items-center gap-2">
				<Zap size={16} class="text-muted-foreground" aria-hidden="true" />
				Quota Ticketmaster API
			</h2>

			{#if data.quotaTM}
				{@const pct = data.quotaTM.total > 0 ? Math.round((data.quotaTM.dispo / data.quotaTM.total) * 100) : 0}
				{@const couleur = pct > 30 ? 'bg-green-500' : pct > 10 ? 'bg-yellow-500' : 'bg-red-500'}
				<div class="rounded-xl border border-border bg-card p-4">
					<div class="mb-3 flex items-end justify-between">
						<div>
							<p class="text-2xl font-semibold tabular-nums text-foreground">
								{data.quotaTM.dispo.toLocaleString('fr-FR')}
								<span class="text-base font-normal text-muted-foreground">
									/ {data.quotaTM.total.toLocaleString('fr-FR')}
								</span>
							</p>
							<p class="text-xs text-muted-foreground">appels restants</p>
						</div>
						<p class="text-sm font-medium text-foreground">{pct} %</p>
					</div>
					<div class="h-2 w-full overflow-hidden rounded-full bg-muted">
						<div class="h-2 rounded-full transition-all {couleur}" style="width: {pct}%"></div>
					</div>
					<div class="mt-3 flex justify-between text-xs text-muted-foreground">
						<span>Remise à zéro vers {formaterReset(data.quotaTM.reset)}</span>
						<span>Mis à jour {formaterMisAJour(data.quotaTM.misAJour)}</span>
					</div>
				</div>
			{:else}
				<div class="rounded-xl border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
					Aucune donnée — quota disponible après le prochain fetch Ticketmaster.
				</div>
			{/if}
		</section>

		<!-- Créer un compte -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground">Créer un compte</h2>

			{#if form?.succes && form?.motDePasseTemp}
				<div class="mb-4 rounded-xl border border-green-500/30 bg-green-500/10 p-4">
					<p class="mb-2 text-sm font-medium text-green-700 dark:text-green-400">
						Compte créé pour <strong>{form.email}</strong>
					</p>
					<p class="text-sm text-green-700 dark:text-green-400">
						Identifiant temporaire :
						<code class="rounded bg-green-500/20 px-2 py-0.5 font-mono font-semibold">
							{form.identifiant}
						</code>
					</p>
					<p class="mt-1 text-sm text-green-700 dark:text-green-400">
						Mot de passe temporaire :
						<code class="rounded bg-green-500/20 px-2 py-0.5 font-mono font-semibold">
							{form.motDePasseTemp}
						</code>
					</p>
					<p class="mt-2 text-xs text-muted-foreground">
						Communiquez ces identifiants à l'utilisateur. À la première connexion, il choisira son propre identifiant et mot de passe.
					</p>
				</div>
			{/if}

			{#if form?.erreur}
				<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
					{form.erreur}
				</p>
			{/if}

			<form
				method="POST"
				action="?/creerCompte"
				class="flex flex-wrap gap-2"
				use:enhance={() => {
					creationEnCours = true;
					return async ({ update }) => {
						await update();
						creationEnCours = false;
						emailNouvelUser = '';
					};
				}}
			>
				<input
					type="email"
					name="email"
					bind:value={emailNouvelUser}
					placeholder="Email de l'utilisateur"
					required
					class="flex-1 min-w-64 rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
				/>
				<button
					type="submit"
					disabled={creationEnCours || !emailNouvelUser.trim()}
					class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
				>
					<UserPlus size={16} />
					Inviter
				</button>
			</form>
		</section>

		<!-- Liste des utilisateurs -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground">
				Utilisateurs ({data.utilisateurs.length})
			</h2>

			<ul class="space-y-2">
				{#each data.utilisateurs as utilisateur (utilisateur.id)}
					<li class="flex items-center justify-between rounded-xl border border-border bg-card px-4 py-3">
						<div class="min-w-0">
							<div class="flex items-center gap-2">
								<span class="font-medium text-card-foreground">{utilisateur.name}</span>
								{#if utilisateur.username}
									<span class="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground font-mono">@{utilisateur.username}</span>
								{/if}
								{#if utilisateur.role === 'admin'}
									<span class="flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
										<ShieldCheck size={11} />
										admin
									</span>
								{/if}
								{#if utilisateur.mustChangeCredentials}
									<span class="rounded-full bg-yellow-500/15 px-2 py-0.5 text-xs text-yellow-700 dark:text-yellow-400">
										premier login en attente
									</span>
								{/if}
							</div>
							<p class="text-sm text-muted-foreground">{utilisateur.email}</p>
						</div>
						{#if utilisateur.id !== data.utilisateur?.id}
							{#if confirmationSupprimer === utilisateur.id}
								<div class="flex items-center gap-2">
									<span class="text-xs text-destructive">Supprimer ?</span>
									<form method="POST" action="?/supprimerCompte" use:enhance={() => async ({ update }) => {
										confirmationSupprimer = null;
										await update();
									}}>
										<input type="hidden" name="userId" value={utilisateur.id} />
										<button
											type="submit"
											class="rounded-md px-2 py-1 text-xs font-medium text-destructive transition-colors hover:bg-destructive/10"
										>
											Confirmer
										</button>
									</form>
									<button
										type="button"
										onclick={() => (confirmationSupprimer = null)}
										class="rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent"
									>
										Annuler
									</button>
								</div>
							{:else}
								<button
									type="button"
									onclick={() => (confirmationSupprimer = utilisateur.id)}
									class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
									aria-label="Supprimer {utilisateur.name}"
								>
									<Trash2 size={16} />
								</button>
							{/if}
						{/if}
					</li>
				{/each}
			</ul>
		</section>

	</div>
</div>
