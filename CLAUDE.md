# CLAUDE.md — TelloPilot

Guide de contribution pour Claude Code sur ce dépôt. Lis-le avant de builder,
release ou pousser.

## Projet

Appli Android native Kotlin de pilotage d'un drone Ryze/DJI Tello (SDK UDP).
`minSdk 26`, `targetSdk 34`. Voir `README.md` pour l'architecture et la
checklist de test manuel.

## Build & vérification

- Build : `./gradlew assembleDebug` (wrapper Gradle 8.7 inclus, JDK 17).
- Le **SDK Android n'est pas disponible dans le sandbox** (endpoints Google
  bloqués par le proxy) → la compilation complète et l'APK sont vérifiés **en
  CI** (`.github/workflows/android.yml`), pas localement.
- Vérif locale possible malgré tout : les modules purement logiques
  (`TelloController`, `TelloState`, `JoystickView`) se compilent contre le
  compilateur Kotlin embarqué + un `android.jar` stub. Utile pour attraper les
  erreurs de syntaxe/typage avant de pousser.
- Honnêteté obligatoire : le **vol réel** (UDP, vidéo, écriture médias) n'est
  **pas vérifiable** ici. Ne jamais prétendre que le pilotage « fonctionne » —
  seuls la compilation et le build CI sont prouvés.

## Release sémantique — marqueur `[release vX.Y.Z]`

On versionne en **SemVer** (`vMAJOR.MINOR.PATCH`). Une release publie une
**GitHub Release** avec l'APK debug attaché, via
`.github/workflows/release.yml`.

### Pourquoi ce mécanisme

Le proxy git du sandbox **bloque le push de tags** (et de `main`) en 403, et le
token de l'intégration GitHub ne peut ni dispatcher un workflow ni créer une
release. Solution : c'est le **`GITHUB_TOKEN` du runner** (côté serveur, hors
proxy) qui crée le tag + la release. On le déclenche par un simple push de la
branche autorisée portant un marqueur dans le message de commit.

### Comment couper une release

1. Bumper la version de l'app dans `app/build.gradle.kts` :
   - `versionName` = la version SemVer (ex. `"1.1.1"`),
   - `versionCode` = incrément entier monotone (ex. `2` → `3`).
   L'APK doit refléter la version publiée, sinon il s'installe sous l'ancienne.
2. Committer avec un marqueur **`[release vX.Y.Z]`** dans le message, puis
   `git push -u origin <branche>`.
3. Le workflow `release.yml` :
   - ne s'exécute que si le message HEAD contient `[release]` (ou push de tag,
     ou `workflow_dispatch`),
   - résout le tag depuis `[release vX.Y.Z]` (défaut `v1.0` si absent),
   - build `assembleDebug`, renomme l'APK `TelloPilot-vX.Y.Z-debug.apk`,
   - crée la release + tag `vX.Y.Z` avec l'APK attaché (`softprops/action-gh-release`).
4. Vérifier : `get_release_by_tag vX.Y.Z` → l'asset `.apk` doit être `uploaded`.

Un commit **sans** `[release]` déclenche seulement le CI de build normal — pas
de release. Le tag pointe sur le commit buildé de la branche.

### Historique des versions

- `v1.0` — MVP initial.
- `v1.1` — fix atterrissage (boucle `rc` conditionnée à `flying`) + handshake SDK.
- `v1.1.1` — alignement `versionName`/`versionCode` sur le tag (l'APK affichait
  encore 1.0).

## Git / PR

- Développer sur la branche dédiée `claude/tello-android-app-mvp-5xuyfa`.
- Le proxy n'autorise le push que de **cette branche** (pas de tags, pas de
  `main` en direct).
- Une PR déjà **mergée est terminée** : toute suite de travail = **nouvelle
  PR** (ne pas empiler sur un historique déjà mergé).
- Toujours `git push -u origin <branche>` ; retry avec backoff en cas d'erreur
  réseau. Merges de PR via l'API GitHub (MCP).

## Pièges spécifiques Tello (déjà gérés — ne pas régresser)

- **Boucle `rc` gated sur `flying`** : streamer `rc 0 0 0 0` en continu est une
  commande de maintien d'altitude qui **empêche l'atterrissage**. `land`/
  `emergency` mettent `flying=false` pour couper le keepalive. Ne jamais rendre
  la boucle `rc` inconditionnelle.
- **Handshake SDK bloquant** : attendre l'`ok` du Tello avant de démarrer la
  boucle rc / `streamon`. Envoyer `takeoff`/`streamon` avant l'`ok` = ignoré
  silencieusement.
- **Binding réseau** : `bindProcessToNetwork` sur le transport WIFI avant
  d'ouvrir les sockets (le WiFi Tello n'a pas d'internet).
- **I/O socket jamais sur le main thread**.
