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

**Bibliothèque**
- Toutes les prises, triables et cherchables (sans se soucier des accents)
- Lecture avec forme d’onde, positionnement au doigt, boucle
- Renommer, partager, supprimer avec corbeille et annulation
- Métadonnées **Broadcast Wave** (`bext`) et **iXML** dans chaque fichier : date, heure,
  entrée, gain, mode de capture, noms de pistes — relues par les logiciels de montage

Les prises sont rangées dans `Musique/Brut`, visibles depuis n’importe quel lecteur ou depuis un
ordinateur branché en USB.

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
