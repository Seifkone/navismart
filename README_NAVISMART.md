# NaviSmart

Fork de [CoMaps](https://github.com/comaps/comaps) pour un GPS autonome avec optimisation économique.
La branche `main` conserve CoMaps ; les modifications du projet sont sur `navismart`.

Le [cahier des charges v2.1](docs/navismart/cahier-des-charges-v2.1.txt) a été récupéré dans la discussion
« Optimiser trajet carburant ». Les décisions ultérieures sont consignées dans
[l’architecture](docs/navismart/architecture.md).

## Premier livrable exécutable

Le module `navismart/economy` réalise les calculs en local avec Java 17, indépendamment
d’Android, de CoMaps, du GPS et du réseau. Il fournit :

- des profils thermiques, flexfuel, électriques et hybrides avec unités explicites ;
- une comparaison de trajets, avec limite de temps en pourcentage et équivalent en minutes ;
- la distinction entre coût de l’énergie consommée et achats effectués pendant le trajet ;
- un inventaire énergétique FIFO, avec réserve, capacité et pertes de recharge ;
- des valeurs inconnues, périmées, indisponibles ou invalides, distinctes de zéro ;
- des intervalles d’incertitude et une règle de stabilité des recommandations ;
- des scénarios synthétiques configurables et des tests d’acceptation automatiques.

Depuis la racine du dépôt, dans PowerShell avec un JDK 17 ou supérieur :

```powershell
./tools/navismart/run-economy.ps1 -Mode Test
./tools/navismart/run-economy.ps1 -Mode Simulate
./tools/navismart/run-economy.ps1 -Mode Simulate -Scenario ./navismart/economy/scenarios/two-routes.properties
```

Le module dispose également d’une compilation Gradle indépendante :

```powershell
./android/gradlew.bat -p navismart/economy check simulate
```

Sous Linux/macOS : `./android/gradlew -p navismart/economy check simulate`.
Gradle télécharge ses outils de compilation au premier lancement ; le moteur ne fait aucun appel réseau.
Les tests PowerShell n’ont aucune dépendance à télécharger.

## Profils véhicule et premier raccordement Android

Les [profils véhicule](docs/navismart/vehicle-profiles.md) sont enregistrables localement.
Le simulateur peut comparer plusieurs motorisations sur les mêmes trajets :

```powershell
./tools/navismart/run-economy.ps1 -Mode Simulate -Scenario ./navismart/economy/scenarios/two-routes.properties -Profiles ./navismart/economy/scenarios/vehicle-profiles.properties -Vehicle all -At '2026-10-13T12:00:00Z'
```

Ces profils et chiffres sont fictifs ; l’horloge fixe rend leur scénario reproductible.
59 scénarios Java couvrent maintenant le moteur et les profils.

Le code Android ajoute **Réglages → NaviSmart · Véhicules**, avec un profil actif,
un éditeur et un aperçu du coût de l’énergie pour le trajet automobile préparé dans CoMaps.
L’aperçu utilise les nombres et unités du moteur embarqué, avec frais et péages inconnus
laissés inconnus. Il n’effectue pas encore de sélection entre plusieurs itinéraires.
Le workflow `NaviSmart Android` exécute dix tests Android et construit un APK arm64
de développement. Sa compilation et les essais sur téléphone sont des validations distinctes.

La [première construction Android est réussie](https://github.com/Seifkone/navismart/actions/runs/38463027554).
L’archive `navismart-android-arm64-debug` contient l’APK NaviSmart Debug ; sa signature
et ses métadonnées ont été vérifiées. Les 69 tests passent. Les essais sur appareil
restent à effectuer, selon le [plan de validation](docs/navismart/device-validation.md).

## État et limites

Le simulateur compare les trajets et programmes de ravitaillement fournis en entrée.
Il ne génère pas encore les itinéraires CoMaps ni un programme optimal d’arrêts.
Ses exemples sont fictifs et ne constituent pas une calibration de la Kia Rio.
Il ne donne aucun conseil de conduite.

L’application Android utilise la base CoMaps avec les premiers réglages NaviSmart.
Le calcul économique est raccordé à un aperçu ; la génération et la sélection des
itinéraires économiques restent à réaliser. Aucun essai sur téléphone n’est attesté.
Les sources publiques sont identifiées, mais les connecteurs de trafic et de prix
ne sont pas encore actifs dans l’application.

Le développement suit la première mission du cahier des charges : valider le moteur
avant de raccorder les itinéraires réels. La calibration OBD-II et les conseils
prédictifs demanderont des mesures indépendantes et leur validation.
