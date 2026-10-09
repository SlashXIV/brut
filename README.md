<p align="center">
  <img src="design/icon/brut-icon.svg" width="112" alt="Icône de Brut">
</p>

<h1 align="center">Brut</h1>

<p align="center"><b>L’enregistreur audio Android qui ne touche pas au son.</b><br>
WAV non compressé · aucun traitement automatique · entrée USB-C au choix</p>

---

La plupart des applications d’enregistrement passent le signal à la moulinette : gain
automatique, réduction de bruit, compression, normalisation. Brut fait l’inverse : ce qui sort du
micro arrive dans le fichier, tel quel. Et quand Android modifie quelque chose en chemin
(rééchantillonnage, effet imposé, entrée changée), Brut l’affiche au lieu de le cacher.

## Fonctionnalités

**Enregistrement**
- WAV non compressé : **16 bit**, **24 bit** ou **32 bit flottant**
- Échantillonnage **44,1 / 48 / 96 kHz**, **mono** ou **stéréo**
- Aucune normalisation, réduction de bruit, compression ni limiteur
- Capture « brute » (`UNPROCESSED`) quand l’appareil la propose ; gain automatique, réduction de
  bruit et annulation d’écho désactivés explicitement
- À 0 dB de gain en 16 bit, le fichier est **bit-exact** : les octets du convertisseur, sans retouche
- Enregistrement écran éteint, chrono et bouton Arrêter dans la notification

**Mesure**
- Crête-mètre LED par canal (échelle IEC 60268-18), RMS, crête maintenue, crête maximale
- Voyant **CLIP** verrouillé jusqu’au toucher
- Durée d’enregistrement restante d’après l’espace libre

**Entrées**
- Détection à chaud des micros **USB-C** et filaires
- Choix manuel de l’entrée : le micro interne n’est jamais imposé
- Entrée réellement utilisée affichée, avec alerte si Android en choisit une autre
- Micro débranché pendant une prise : la prise est arrêtée et sauvegardée, jamais poursuivie en
  douce sur le micro interne

**Réglages**
- Gain d’entrée par faders, **gauche et droite séparés** ou liés, cran à 0 dB et pas de 0,5 dB
- Vu-mètre à aiguille (0 VU = −18 dBFS) ou crête-mètres LED, au choix
- Mode de capture imposable (automatique, brut, sans gain auto, standard)
- Réglages retrouvés à chaque ouverture

**Outils d’ingé son**
- **Pré-enregistrement** (2, 5 ou 10 s) : l’attaque d’un son qui t’a surpris n’est pas perdue
- **Piste de sécurité** à −6, −12 ou −18 dB, enregistrée en parallèle
- **Déclenchement sur seuil** : REC arme la prise, qui démarre dès que le son dépasse le seuil
- **Repères** pendant la prise, d’un gros bouton ou depuis la notification
- **Écoute au casque** du signal enregistré
- Sonie **EBU R128** (instantanée, court terme, intégrée), **crête vraie** et **analyseur de spectre**

**Accès rapide**
- **Préréglages** Interview, Concert, Ambiance et Voix off : format et outils en un geste, jamais
  le gain ni l’entrée
- **Tuile des Paramètres rapides** : lancer ou arrêter une prise depuis le volet, même écran
  verrouillé, sans ouvrir l’appli
- **Widget** d’écran d’accueil : REC, chrono, niveau, voyant CLIP et bouton Repère
- **Raccourcis** sur l’icône (appui long) : Enregistrer, Prises

**Accessibilité et langues**
- **TalkBack** : niveaux de chaque voie, voyants de saturation annoncés dès qu’ils s’allument,
  sonie, spectre, durée de la prise et messages
- Contrastes du texte vérifiés (au moins 4,5:1)
- Interface en **français** et en **anglais**, au choix dans les réglages Android

**Robustesse terrain**
- Prise interrompue (plantage, batterie, appli tuée) **récupérée** au démarrage suivant
- Format **RF64** automatique au-delà de 4 Go : pas de limite de durée
- Micro coupé par un appel : la prise continue et un **repère** marque l’endroit
- Capture qui lâche en pleine prise : reprise automatique dans le même fichier
- Alerte batterie faible et espace bas, arrêt propre avant la coupure
- Dossier de destination au choix, **carte SD** comprise

**Bibliothèque**
- Toutes les prises, triables et cherchables (sans se soucier des accents)
- Lecture avec forme d’onde, positionnement au doigt, boucle
- Renommer, partager, supprimer avec corbeille et annulation
- Métadonnées **Broadcast Wave** (`bext`) et **iXML** dans chaque fichier : date, heure,
  entrée, gain, mode de capture, noms de pistes — relues par les logiciels de montage

**Édition légère, jamais destructive**
- **Rogner** le début et la fin entre deux poignées IN et OUT, qui s’aimantent aux repères ;
  seule la sélection est jouée pendant le réglage
- **Découper aux repères** : un fichier par morceau
- Chaque export est un **nouveau fichier** : l’original n’est jamais modifié
- À résolution d’origine, l’extrait est **identique octet par octet** à la portion de la prise
- Conversion au choix vers 16, 24 ou 32 bit flottant (sans tramage, écrêtages signalés) et
  extraction d’une seule voie ; la fréquence d’échantillonnage reste celle de la prise
- L’horodatage BWF de l’extrait est décalé d’autant : la synchronisation avec une caméra reste juste

Les prises sont rangées par défaut dans `Musique/Brut`, visibles depuis n’importe quel lecteur ou
depuis un ordinateur branché en USB, ou dans le dossier de ton choix.

## Installation

Télécharger le dernier `Brut-vX.Y.Z.apk` dans les
[Releases](../../releases/latest) et l’ouvrir sur le téléphone (Android 8.0 ou plus récent).

## Vie privée

Pas de compte, pas de réseau, pas de publicité, pas de statistiques. La seule permission sensible
est le micro.

## Compiler

```sh
./gradlew testDebugUnitTest assembleDebug
```

JDK 17 ou plus récent requis (celui d’Android Studio convient).

## Licence

[GNU AGPL v3](LICENSE). Polices Barlow et Share Tech Mono sous licence SIL OFL 1.1.
