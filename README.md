# TelloPilot

Application Android native (Kotlin) pour piloter un drone **DJI / Ryze Tello**
(modèle standard, pas l'EDU), en alternative à l'appli officielle et à aTello.

Cible : **Android 12** (testé visé sur OnePlus 7T). `minSdk 26`, `targetSdk 34`.

> ⚠️ **État de validation.** Ce projet a été développé sans drone ni téléphone
> disponibles. Ce qui est **vérifié** : la compilation Kotlin des modules logiques
> et le build de l'APK debug via GitHub Actions. Ce qui n'est **PAS vérifié** : le
> pilotage réel, la liaison UDP, le décodage vidéo et l'écriture des médias — ils
> dépendent du matériel et devront être validés au premier vol (voir la checklist).

## Fonctionnalités MVP

| # | Fonction | Implémentation |
|---|----------|----------------|
| 1 | Connexion + SDK mode | `TelloController` : bind WiFi, envoi de `command` ×2, statut affiché |
| 2 | Double joystick (mode 2) | `JoystickView` custom (Canvas, sans asset), sortie normalisée [-1,1] → canaux `rc` |
| 3 | Takeoff / Land / EMERGENCY | Boutons ; EMERGENCY rouge bien visible coupe les moteurs |
| 4 | Toggle NORMAL / RAPIDE | Multiplicateur d'amplitude des sticks (0.5 vs 1.0), état visuel |
| 5 | Vidéo live | `TelloVideo` : UDP 11111 → reconstruction NAL Annex-B → `MediaCodec` → `TextureView` |
| 6 | Photo | Capture de la frame courante du `TextureView` → JPEG dans `Pictures/TelloPilot/` (MediaStore) |
| 7 | Enregistrement vidéo | Mux du flux H264 en MP4 via `MediaMuxer` dans `Movies/TelloPilot/` (MediaStore) |
| 8 | Télémétrie | Batterie %, altitude (h), distance sol (tof) lues depuis le state UDP 8890 |

## Architecture

```
app/src/main/java/com/tellopilot/
├── MainActivity.kt     UI, permissions runtime, binding réseau WiFi, câblage sticks
├── TelloController.kt  Sockets UDP 8889/8890, SDK mode, boucle rc 20 Hz, parsing state
├── TelloVideo.kt       UDP 11111, split NAL, MediaCodec (affichage) + MediaMuxer (REC) + JPEG
├── JoystickView.kt     Custom View tactile réutilisable, auto-recentrage
└── TelloState.kt       Parsing thread-safe de la chaîne de télémétrie
```

### Points techniques notables

- **Piège réseau résolu** : le WiFi du Tello n'a pas d'internet, Android garde
  donc la data mobile par défaut. On utilise `ConnectivityManager.requestNetwork`
  (transport **WIFI**) puis `bindProcessToNetwork(network)` **avant** d'ouvrir les
  sockets, sinon l'UDP n'atteint jamais le drone.
- **Boucle rc 20 Hz** : le Tello atterrit seul après ~15 s sans commande. Un thread
  envoie `rc a b c d` toutes les 50 ms tant qu'on est connecté.
- **Threads dédiés** : toutes les sockets et le décodage tournent hors du main
  thread ; l'état est partagé via des `Atomic*`.
- **Scoped storage** : aucune permission `WRITE_EXTERNAL_STORAGE`. Photos et vidéos
  passent par `MediaStore` (avec `IS_PENDING`) → visibles dans la galerie sans
  permission de stockage sur Android 12.
- **H264** : découpe sur les start codes Annex-B (`00 00 01`), repérage SPS (type 7)
  et PPS (type 8) pour configurer le décodeur et fournir les `csd` au muxer.

## Build

Projet Gradle autosuffisant (wrapper inclus). Aucune dépendance propriétaire.

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

### CI

`.github/workflows/android.yml` : à chaque push, set up JDK 17 + Android SDK,
build `assembleDebug` et **upload l'APK debug en artifact** (`tellopilot-debug-apk`).

## Installation sur le téléphone

1. Récupérer l'APK debug (artifact CI ou build local).
2. Autoriser les sources inconnues, installer l'APK.
3. Au premier lancement, accorder la permission **Localisation** (nécessaire pour
   identifier le WiFi Tello sur Android 12 ; remplacée par `NEARBY_WIFI_DEVICES`
   sur Android 13+).

## ✅ Checklist de test manuel (à faire avec le drone)

Rien ci-dessous n'a pu être vérifié en sandbox. À valider dans l'ordre :

- [ ] **WiFi** : allumer le Tello, connecter le téléphone au réseau `TELLO-XXXXXX`.
      Laisser Android afficher « connecté sans internet » (ne PAS « oublier » le réseau).
- [ ] **Connexion** : ouvrir l'app, appuyer **CONNECT**. Le statut doit passer à
      « WiFi lié — connexion au Tello » puis afficher une réponse batterie.
- [ ] **Télémétrie** : vérifier que Bat %, Alt et Sol se mettent à jour.
- [ ] **Takeoff** : hélices dégagées, appuyer **TAKEOFF**. Le drone décolle et tient
      en vol stationnaire (la boucle rc doit l'empêcher de se reposer après 15 s).
- [ ] **Sticks** : stick gauche = gaz (haut/bas) + lacet (gauche/droite) ; stick droit
      = avant/arrière + translation latérale. Vérifier les sens et l'absence de dérive
      au centre.
- [ ] **NORMAL / RAPIDE** : confirmer que RAPIDE augmente l'amplitude (autorité pleine).
- [ ] **Vidéo** : l'image live doit apparaître dans le fond après CONNECT (streamon
      est envoyé automatiquement). Vérifier latence/fluidité.
- [ ] **Photo** : appuyer **PHOTO**, vérifier le JPEG dans la galerie
      (`Pictures/TelloPilot/`).
- [ ] **REC** : appuyer **REC**, voler quelques secondes, **STOP**. Vérifier que le
      MP4 est **lisible dans la galerie** (`Movies/TelloPilot/`).
- [ ] **LAND** puis **EMERGENCY** (au sol, hélices dégagées) : vérifier la coupure moteurs.

## Limites connues / à vérifier au premier vol

- **Sens des axes des sticks** : la convention mode-2 est implémentée mais les signes
  (notamment roll/yaw) doivent être confirmés en vol et inversés si besoin dans
  `MainActivity.setupJoysticks()`.
- **Muxing MP4** : chaque NAL VCL est écrit comme un échantillon avec un PTS
  synthétique à 30 fps (le flux Tello ne porte pas d'horodatage). Le fichier devrait
  être lisible ; si la durée/cadence semble fausse, ajuster `FPS` dans `TelloVideo`.
- **Résolution vidéo** : initialisée à 960×720 (flux standard Tello) puis corrigée par
  le décodeur via `INFO_OUTPUT_FORMAT_CHANGED`.
- **Aucune validation hardware** n'a été possible en sandbox : pilotage, liaison UDP,
  décodage et écriture médias sont à confirmer au premier vol réel.

## Hors scope (phase 2)

Manette Bluetooth/OTG, autopilotes (RTH/orbit/dronie), réglages bitrate/résolution,
mode VR/FPV goggles.
