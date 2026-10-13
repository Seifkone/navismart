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

## État et limites

Le simulateur compare les trajets et programmes de ravitaillement fournis en entrée.
Il ne génère pas encore les itinéraires CoMaps ni un programme optimal d’arrêts.
Ses exemples sont fictifs et ne constituent pas une calibration de la Kia Rio.
Il ne donne aucun conseil de conduite.

L’application Android du fork est encore la base CoMaps. Le calcul économique n’est
pas encore raccordé à son interface. Aucun APK NaviSmart modifié n’a été livré.
Les sources publiques sont identifiées, mais les connecteurs de trafic et de prix
ne sont pas encore actifs dans l’application.

Le développement suit la première mission du cahier des charges : valider le moteur
avant de raccorder les itinéraires réels. La calibration OBD-II et les conseils
prédictifs demanderont des mesures indépendantes et leur validation.
