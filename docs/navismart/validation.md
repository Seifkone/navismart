# Validation des jalons NaviSmart

## Travaux du 14 octobre 2026

- 110 scénarios Java indépendants réussis : 39 économie, 20 profils, 18 carburants,
  13 protocoles OBD, quatre cycles de collecte, quatre scénarios d’ordonnancement,
  huit événements routiers et quatre transferts de profils.
- Vérification Gradle indépendante réussie avec Java 17 ; le premier essai sous
  bac à sable ne pouvait pas joindre son propre processus Gradle sur localhost.
  Le même contrôle avec les permissions locales nécessaires est réussi.
- Import du ZIP gouvernemental réel : 9 818 stations, 31 705 prix, aucun champ rejeté.
- Import du DATEX II réel : 751 fiches géolocalisées, avec dates et validité distinctes.
- Ressources françaises et anglaises présentes avec les mêmes identifiants.
- Onze tests Python de l’analyse locale des journaux sont réussis : unités, données
  absentes, intégrales bornées à leur couverture, horloge, séparation des sessions
  et refus d’écraser les fichiers sources ou un rapport existant.
- Le premier APK stations/diagnostic OBD (`95cc21a`) et ses dix tests Android ont
  [réussi](https://github.com/Seifkone/navismart/actions/runs/38594381183).

La comparaison des détours, les avis routiers et le transfert Android des profils
ont été compilés sur `51004ad` dans le [workflow réussi](https://github.com/Seifkone/navismart/actions/runs/38596922868).
Les seize tests Android ont réussi ; les six nouveaux ciblent
les conversions de sous-trajets et l’invalidation d’une comparaison devenue ancienne.
Aucun de ces contrôles ne remplace l’installation, le guidage et la connexion OBD
sur véhicule. Les flux réels ne sont pas intégrés aux tests automatiques : ceux-ci
restent déterministes et hors ligne.

La collecte OBD dispose ensuite d’un service de premier plan `connectedDevice`,
d’un démarrage explicite, d’une notification d’arrêt et de limites de conservation.
Ses tests de cycle vérifient les réponses tardives, les sessions remplacées et
les valeurs historiques. Les contraintes de permission et de vie du service Android
doivent encore être vérifiées sur appareil.

## Historique du 13 octobre 2026

Validation locale le 13 octobre 2026, Java 17 et Gradle 8.14.4.

- Compilation Java indépendante : réussie.
- 39 scénarios économiques et 20 scénarios de profils : réussis ; résultats dans `acceptance-results.txt`.
- Compilation et tâche `check` du projet Gradle indépendant : réussies.
- Simulation intégrée et simulation configurable UTF-8 : exécutées.
- Comparaison de trois profils synthétiques enregistrés : exécutée avec une horloge fixe.
- Workflow GitHub du moteur et des profils : réussi sur le commit `34b8cd3`.
- Compilation Android arm64 et dix tests des adaptateurs : réussis sur `6475d46`.
- Vérification des espaces et fins de lignes des modifications Git : réussie.

Les tests vérifient des situations économiques et contraintes, notamment inventaire
FIFO, réserves à chaque étape, capacité, pertes de recharge, mélange hybride explicite,
défaillance des sources, dates, intervalle temporel, hystérésis et stabilité du choix.
Ils n’exercent aucun service externe et ne dépendent pas de l’état du trafic réel.

Le fichier configurable `two-routes.properties` doit recommander `eco` : le trajet
rapide consomme 6 L et ajoute un péage de 10 €, soit 20,8 € ; le trajet alternatif
consomme 7,2 L à 1,8 €/L, soit 12,96 €, en respectant exactement les 5 minutes
supplémentaires admises sur 100 minutes. Ce sont des données fictives exactes de test.

Le scénario d’achat partiel doit produire 30,4 € d’énergie consommée et 18,6 €
payés à la station, avec 6,2 € de stock résiduel. Les deux coûts ne s’additionnent pas.

Cette validation ne prouve pas un gain de consommation sur route réelle ni la précision
du modèle d’une voiture. Le raccordement initial aux itinéraires CoMaps possède cinq
tests Android, et le stockage des profils en possède cinq autres. Leur exécution et
la construction de l’APK sont traitées par le workflow Android séparé. Aucun essai
sur téléphone, trafic réel, OBD-II ou conseil prédictif n’est attesté par les tests Java.

## APK de développement

Le [workflow Android réussi](https://github.com/Seifkone/navismart/actions/runs/38463027554)
fournit l’archive `navismart-android-arm64-debug`, conservée 14 jours par GitHub.
Elle contient `NaviSmart-26101306-fdroid-debug.apk` (148 426 649 octets).

Contrôles effectués sur le fichier téléchargé avec les outils Android Build Tools 36 :

- Identifiant : `app.navismart.fdroid.debug`.
- Nom : `NaviSmart Debug`.
- Architecture : `arm64-v8a`.
- Version : `2026.10.13-6-FDroid-debug` ; code `26101306`.
- Android minimal : API 21 ; cible et compilation : API 36.
- Vérification de signature avec `apksigner` : réussie (schémas v1 et v2).
- Certificat de développement public CoMaps : SHA-256 `0cccc7c349bcc0005f53aa1a58f42b1c8ec6a387513f7c33576390842eac301a`.
- SHA-256 de l’APK : `64b172b657ba78690a0ecbe1b423c0d10437e1757358a001c201f8de062b8f50`.

Le premier emballage de l’APK avait échoué : le renommage de l’application avait
modifié l’alias recherché dans la clé de développement existante. Le commit `6475d46`
conserve l’alias réel `CoMaps Debug` ; la construction suivante est réussie.
La signature est celle du développement, pas une clé de distribution NaviSmart.

Les 69 tests (39 économiques, 20 profils, 10 adaptateurs Android) ne remplacent pas
les essais de l’interface, du GPS et du téléchargement des cartes sur appareil.
