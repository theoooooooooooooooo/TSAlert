# TSAlert — Notes sur l'écoute vocale (Vosk)

Détection vocale hors ligne du mot « SOS » via Vosk + foreground service, pour
un usage mains-libres. Ce document liste honnêtement les limites et les enjeux.

## Honnêteté technique — à connaître

### Pas de relance automatique au boot
L'auto-démarrage du service micro après un redémarrage **n'est pas fiable**.
Depuis Android 12+, démarrer un foreground service de type `microphone` depuis
le background (ex. `BOOT_COMPLETED`) est restreint/interdit : le système lève
une `ForegroundServiceStartNotAllowedException` ou bloque l'accès au micro.

➡️ **Décision : pas de relance auto après reboot.** L'utilisateur doit rouvrir
l'application et réactiver l'écoute via le switch « Activer l'écoute vocale ».
Le démarrage se fait donc toujours app au premier plan (jamais en background).

### Impact batterie
L'écoute continue du micro + l'inférence Vosk tournent en permanence tant que
le service est actif. Cela **consomme de la batterie de façon notable** et
empêche certaines optimisations de veille. Le mode grammaire (restreint à
`["sos", "[unk]"]`) limite la charge CPU et les faux positifs, mais ne supprime
pas le coût de l'écoute permanente.

### Taille de l'APK (~50 Mo)
Le modèle Vosk français small ajoute **environ 50 Mo** à l'APK (placé dans
`assets/`). Les `abiFilters` sont limités à `arm64-v8a` et `x86_64` pour réduire
le poids des libs natives `.so`.

### Vie privée
L'écoute permanente du micro est un **enjeu de vie privée majeur**. Points clés
signalés à l'utilisateur dans l'UI (carte « Activer l'écoute vocale ») :
- l'app écoute le micro en continu, **même fermée**, une fois activée ;
- la reconnaissance est **100 % hors ligne** (rien n'est envoyé sur Internet) ;
- l'écoute consomme de la batterie ;
- l'utilisateur garde le contrôle via le switch (activation/désactivation).

## Anti faux positif

Sur une app SOS, un déclenchement accidentel enverrait un vrai SMS. La détection
**n'envoie donc jamais directement** : sur « sos » reconnu, le service poste une
**notification full-screen** (`setFullScreenIntent`) qui ouvre `MainActivity`,
laquelle lance un **compte à rebours d'annulation de 5 s** avec un bouton
« Annuler ». L'envoi réel via `AlertSender` n'a lieu qu'à l'expiration.
Un anti-rebond (cooldown ~15 s) évite d'empiler les déclenchements.

On ne fait **jamais** de `startActivity` direct depuis le background : on passe
par la full-screen intent (cas d'urgence autorisé), qui réveille aussi l'écran
verrouillé (`setShowWhenLocked` / `setTurnScreenOn`).

### Confirmation vocale « oui » / « non » (mains-libres)
Pour rester réellement mains-libres, la confirmation est aussi **vocale**. La
grammaire est étendue à `["sos", "oui", "non", "[unk]"]`. Pendant le compte à
rebours :
- **« non »** (ou bouton « Annuler ») → annulation immédiate, aucun SMS ;
- **« oui »** (ou bouton « Envoyer ») → envoi immédiat ;
- **fin du délai (~5 s) sans réponse → ENVOI** (priorité sécurité : si la
  victime ne peut plus parler ni toucher l'écran, l'alerte part quand même).

Des **bips répétés + une vibration** accompagnent le compte à rebours pour
prévenir, en mains-libres, qu'un envoi est imminent et laisser le temps de dire
« non ». Le service détecte oui/non et relaie à MainActivity via les actions
`SOS_CONFIRM` / `SOS_CANCEL` ; ce `startActivity` est émis alors que l'Activity
de compte à rebours est déjà au premier plan (autorisé). En cas d'ambiguïté,
« non » est prioritaire sur « oui ».

### Garde d'état (un seul cycle à la fois) — plus de cooldown fixe
À la place d'un ancien cooldown fixe de 15 s, on utilise une **garde d'état** :
tant qu'un cycle d'alerte est actif, tout nouveau « sos » est ignoré. Dès que le
cycle se résout, la garde est libérée :
- **annulation (« non »/bouton Annuler)** → libération **immédiate** : on peut
  redire « sos » tout de suite ;
- **envoi (« oui »/bouton/timeout)** → court anti-doublon `SEND_GUARD_MS` (~3 s)
  pour ne pas renvoyer un SMS dans la foulée.

MainActivity notifie la résolution au service via `notifyCycleResolved(sent)`
(même process → champ statique partagé). Un garde-fou `CYCLE_MAX_MS` (~8 s)
auto-libère la garde si l'Activity ne notifie jamais (ex. process tué).

### Numéro du contact d'urgence configurable
Le numéro n'est plus en dur : il est saisi dans la carte « Contact d'urgence »
et persisté en **SharedPreferences** (`AlertSender.getNumero/setNumero`).
`AlertSender.sendAlert()` lit ce numéro à l'envoi ; valeur par défaut
`AlertSender.DEFAULT_NUMERO` = « +1 555-123-4567 » si le champ est vide.
Pour tester l'arrivée sur l'émulateur, saisir le **numéro de l'émulateur lui-même**.

## Où déposer le modèle Vosk

Voir aussi `app/src/main/assets/model-fr/PLACER_LE_MODELE_ICI.txt`.

- Modèle : **vosk-model-small-fr-0.22** (https://alphacephei.com/vosk/models)
- Emplacement : décompresser le **contenu** du modèle directement sous
  `app/src/main/assets/model-fr/` (dossiers `am/`, `conf/`, `graph/`,
  `ivector/`, …), **sans** le dossier intermédiaire de l'archive.
- Le nom `model-fr` est référencé par `SosListenerService.MODEL_ASSET`.

### ⚠️ Fichier `uuid` requis par StorageService
`StorageService.unpack` lit un fichier **`uuid`** à la racine du dossier modèle
(`assets/model-fr/uuid`) pour décider s'il doit re-décompresser le modèle vers
le stockage interne. Les modèles génériques téléchargés sur alphacephei.com
**n'incluent pas** ce fichier → `java.io.FileNotFoundException: model-fr/uuid`.
Un fichier `uuid` contenant une simple ligne (un identifiant quelconque) est
fourni dans `assets/model-fr/uuid`. Si tu remplaces le modèle par une nouvelle
version, change la valeur du `uuid` pour forcer la re-décompression.
