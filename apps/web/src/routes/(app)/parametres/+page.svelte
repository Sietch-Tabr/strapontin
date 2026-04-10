<script lang="ts">
	import { enhance } from '$app/forms';
	import { Sun, Moon, Save, KeyRound, User, Download, Upload } from '@lucide/svelte';
	import type { PageData, ActionData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();

	// Valeurs extraites une fois à l'init — intentionnel (formulaire local)
	const { config, theme: themeInitial, utilisateur } = data;

	let departements = $state<string>(config?.departements?.join(', ') ?? '');
	let rayonKm = $state<number>(config?.rayonKm ?? 50);
	let filtreDisponibles = $state<boolean>(config?.filtreDisponibles ?? true);
	let notificationsEmail = $state<boolean>(config?.notificationsEmail ?? false);

	let nom = $state<string>(utilisateur?.name ?? '');
	let theme = $state<string>(themeInitial);

	let enregistrementConfig = $state(false);
	let enregistrementCompte = $state(false);
	let enregistrementMdp = $state(false);
	let importEnCours = $state(false);
	let modeImport = $state<'merger' | 'ecraser'>('merger');
</script>

<div class="p-6 lg:p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-semibold text-foreground">Paramètres</h1>
	</div>

	<div class="max-w-xl space-y-10">

		<!-- Thème -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground">Apparence</h2>
			<form method="POST" action="?/theme" use:enhance={() => {
				return async ({ result }) => {
					if (result.type === 'success') {
						// Rechargement complet pour appliquer class="dark" sur <html>
						window.location.reload();
					}
				};
			}}>
				<button
					type="submit"
					class="flex items-center gap-3 rounded-xl border border-border bg-card px-4 py-3 text-sm font-medium text-card-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
				>
					{#if theme === 'dark'}
						<Moon size={18} />
						Mode sombre actif — passer en clair
					{:else}
						<Sun size={18} />
						Mode clair actif — passer en sombre
					{/if}
				</button>
			</form>
		</section>

		<!-- Préférences de recherche -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground">Recherche de concerts</h2>

			{#if form?.succes === 'config'}
				<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
					Préférences enregistrées.
				</p>
			{/if}
			{#if form?.erreur && form?.section === 'config'}
				<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
					{form.erreur}
				</p>
			{/if}

			<form
				method="POST"
				action="?/config"
				class="space-y-4"
				use:enhance={() => {
					enregistrementConfig = true;
					return async ({ update }) => {
						await update({ reset: false });
						enregistrementConfig = false;
					};
				}}
			>
				<div>
					<label for="departements" class="mb-1.5 block text-sm font-medium text-foreground">
						Départements
					</label>
					<input
						id="departements"
						type="text"
						name="departements"
						bind:value={departements}
						placeholder="75, 92, 93, 94…"
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
					<p class="mt-1 text-xs text-muted-foreground">Codes séparés par des virgules.</p>
				</div>

				<div>
					<label for="rayonKm" class="mb-1.5 block text-sm font-medium text-foreground">
						Rayon ({rayonKm} km)
					</label>
					<input
						id="rayonKm"
						type="range"
						name="rayonKm"
						bind:value={rayonKm}
						min="0"
						max="500"
						step="10"
						class="w-full accent-foreground"
					/>
					<div class="flex justify-between text-xs text-muted-foreground">
						<span>0 km</span>
						<span>500 km</span>
					</div>
				</div>

				<div class="flex items-center gap-3">
					<input
						id="filtreDisponibles"
						type="checkbox"
						name="filtreDisponibles"
						bind:checked={filtreDisponibles}
						class="h-4 w-4 rounded border-border accent-foreground"
					/>
					<label for="filtreDisponibles" class="text-sm text-foreground">
						Masquer les concerts dont les billets ne sont plus disponibles
					</label>
				</div>

				<div class="flex items-center gap-3">
					<input
						id="notificationsEmail"
						type="checkbox"
						name="notificationsEmail"
						bind:checked={notificationsEmail}
						class="h-4 w-4 rounded border-border accent-foreground"
					/>
					<label for="notificationsEmail" class="text-sm text-foreground">
						Recevoir les alertes par email
					</label>
				</div>

				<button
					type="submit"
					disabled={enregistrementConfig}
					class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
				>
					<Save size={16} />
					{enregistrementConfig ? 'Enregistrement…' : 'Enregistrer'}
				</button>
			</form>
		</section>

		<!-- Compte -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground">Mon compte</h2>

			{#if form?.succes === 'compte'}
				<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
					Nom mis à jour.
				</p>
			{/if}
			{#if form?.erreur && form?.section === 'compte'}
				<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
					{form.erreur}
				</p>
			{/if}

			<form
				method="POST"
				action="?/compte"
				class="space-y-4"
				use:enhance={() => {
					enregistrementCompte = true;
					return async ({ update }) => {
						await update({ reset: false });
						enregistrementCompte = false;
					};
				}}
			>
				<div>
					<label for="nom" class="mb-1.5 block text-sm font-medium text-foreground">
						Nom d'affichage
					</label>
					<input
						id="nom"
						type="text"
						name="nom"
						bind:value={nom}
						maxlength="100"
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
				</div>

				<button
					type="submit"
					disabled={enregistrementCompte}
					class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
				>
					<User size={16} />
					{enregistrementCompte ? 'Mise à jour…' : 'Mettre à jour'}
				</button>
			</form>
		</section>

		<!-- Mot de passe -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground">Mot de passe</h2>

			{#if form?.succes === 'motDePasse'}
				<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
					Mot de passe modifié. Les autres sessions ont été révoquées.
				</p>
			{/if}
			{#if form?.erreur && form?.section === 'motDePasse'}
				<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
					{form.erreur}
				</p>
			{/if}

			<form method="POST" action="?/motDePasse" class="space-y-4" use:enhance={() => {
				enregistrementMdp = true;
				return async ({ update }) => {
					await update();
					enregistrementMdp = false;
				};
			}}>
				<div>
					<label for="motDePasseActuel" class="mb-1.5 block text-sm font-medium text-foreground">
						Mot de passe actuel
					</label>
					<input
						id="motDePasseActuel"
						type="password"
						name="motDePasseActuel"
						autocomplete="current-password"
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
				</div>

				<div>
					<label for="motDePasseNouveau" class="mb-1.5 block text-sm font-medium text-foreground">
						Nouveau mot de passe
					</label>
					<input
						id="motDePasseNouveau"
						type="password"
						name="motDePasseNouveau"
						minlength="8"
						autocomplete="new-password"
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
				</div>

				<div>
					<label for="motDePasseConfirmation" class="mb-1.5 block text-sm font-medium text-foreground">
						Confirmer le nouveau mot de passe
					</label>
					<input
						id="motDePasseConfirmation"
						type="password"
						name="motDePasseConfirmation"
						minlength="8"
						autocomplete="new-password"
						class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
					/>
				</div>

				<button
					type="submit"
					disabled={enregistrementMdp}
					class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
				>
					<KeyRound size={16} />
					{enregistrementMdp ? 'Modification…' : 'Modifier le mot de passe'}
				</button>
			</form>
		</section>

		<!-- Export / Import préférences -->
		<section>
			<h2 class="mb-4 text-base font-medium text-foreground">Export / Import</h2>
			<p class="mb-4 text-sm text-muted-foreground">
				Exportez vos artistes et salles suivis (JSON), ou importez un fichier existant.
			</p>

			<!-- Export -->
			<div class="mb-6">
				<h3 class="mb-2 text-sm font-medium text-foreground">Exporter mes préférences</h3>
				<a
					href="/api/preferences"
					download
					class="inline-flex items-center gap-2 rounded-md border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
				>
					<Download size={16} />
					Télécharger le fichier JSON
				</a>
			</div>

			<!-- Import -->
			<div>
				<h3 class="mb-2 text-sm font-medium text-foreground">Importer des préférences</h3>

				{#if form?.succes === 'import'}
					<p class="mb-4 rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 dark:text-green-400">
						{form.message}
					</p>
				{/if}
				{#if form?.erreur && form?.section === 'import'}
					<p class="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
						{form.erreur}
					</p>
				{/if}

				<form
					method="POST"
					action="?/importerPreferences"
					enctype="multipart/form-data"
					class="space-y-4"
					use:enhance={() => {
						importEnCours = true;
						return async ({ update }) => {
							await update({ reset: true });
							importEnCours = false;
						};
					}}
				>
					<div>
						<label for="fichier-import" class="mb-1.5 block text-sm font-medium text-foreground">
							Fichier JSON
						</label>
						<input
							id="fichier-import"
							type="file"
							name="fichier"
							accept=".json,application/json"
							required
							class="block w-full rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground file:mr-3 file:rounded file:border-0 file:bg-muted file:px-2 file:py-1 file:text-xs file:font-medium file:text-foreground"
						/>
					</div>

					<div class="flex items-start gap-2.5">
						<input
							id="importer-config"
							type="checkbox"
							name="importerConfig"
							class="mt-0.5 h-4 w-4 rounded border-border accent-foreground"
						/>
						<label for="importer-config" class="text-sm text-foreground">
							<span class="font-medium">Importer également la configuration</span>
							<span class="text-muted-foreground"> — départements, rayon, filtres</span>
						</label>
					</div>

					<div class="space-y-2">
						<p class="text-sm font-medium text-foreground">Gestion des doublons</p>
						<label class="flex items-start gap-2.5 cursor-pointer">
							<input
								type="radio"
								name="mode"
								value="merger"
								bind:group={modeImport}
								class="mt-0.5 accent-foreground"
							/>
							<span class="text-sm text-foreground">
								<span class="font-medium">Fusionner</span>
								<span class="text-muted-foreground"> — ajoute les artistes et salles absents, conserve l'existant</span>
							</span>
						</label>
						<label class="flex items-start gap-2.5 cursor-pointer">
							<input
								type="radio"
								name="mode"
								value="ecraser"
								bind:group={modeImport}
								class="mt-0.5 accent-foreground"
							/>
							<span class="text-sm text-foreground">
								<span class="font-medium">Écraser</span>
								<span class="text-muted-foreground"> — remplace toutes vos préférences par celles du fichier</span>
							</span>
						</label>
						{#if modeImport === 'ecraser'}
							<p class="rounded-md border border-amber-300/50 bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:border-amber-700/50 dark:bg-amber-950/40 dark:text-amber-300">
								Attention : vos artistes et salles actuels seront supprimés avant l'import.
							</p>
						{/if}
					</div>

					<button
						type="submit"
						disabled={importEnCours}
						class="flex items-center gap-2 rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background transition-colors hover:bg-foreground/90 disabled:opacity-50"
					>
						<Upload size={16} />
						{importEnCours ? 'Import en cours…' : 'Importer'}
					</button>
				</form>
			</div>
		</section>

	</div>
</div>
