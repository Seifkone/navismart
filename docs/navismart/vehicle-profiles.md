# Profils véhicule — phase 2

Le moteur accepte maintenant un catalogue de véhicules enregistré avec un format
local versionné, indépendant d’Android et du réseau. Chaque profil conserve sa
motorisation, ses stocks d’énergie, sa politique de temps et de gain minimal,
ainsi que la masse, la boîte et les rapports de transmission s’ils sont connus.
Les paramètres mécaniques sont conservés ; ils ne servent pas encore à prédire
la décélération ou la consommation en fonction de la vitesse.

## Android

Un écran est ajouté dans **Réglages → NaviSmart · Véhicules** : ajout, modification,
sélection du profil actif et sauvegarde dans le stockage privé de l’application.
Le catalogue est sauvegardé en une seule valeur ; une lecture invalide est signalée
sans remplacer les données existantes. Aucune donnée du profil n’est transmise.

Les champs laissés vides restent inconnus. Les bornes basse et haute sont facultatives
mais doivent être fournies ensemble. Leur absence n’est pas interprétée comme une
incertitude nulle. Un changement de carburant exige la confirmation que consommation
et prix correspondent à cette énergie ; aucun coefficient E85 n’est appliqué.

Un niveau d’énergie modifié exige une confirmation explicite des niveaux actuels.
Modifier le nom ou une consommation ne rajeunit pas l’observation du stock.
Les données d’un moteur hybride comprennent un réservoir et une batterie, mais une
répartition explicite par tronçon reste nécessaire pour effectuer un calcul fiable.
L’éditeur initial couvre un réservoir, une batterie ou cette paire ; la bibliothèque
permet plusieurs réservoirs thermiques, mais l’éditeur de véhicules GPL bicarburants
et de mélanges variables restera à compléter.

Le moteur économique est inclus comme module Gradle de l’application. Un premier
adaptateur lit la distance numérique et son unité ainsi que le temps en secondes du
trajet automobile CoMaps préparé. Il fournit un aperçu énergétique dans les réglages,
avant le guidage. Les kilomètres sont convertis sans parser le texte localisé ; les
distances affichées par CoMaps peuvent être arrondies, donc leur précision reste non
quantifiée. Les péages et autres frais absents restent inconnus. Cet aperçu affiche
l’ancienneté des données et la faisabilité des niveaux, sans intervenir sur le trajet.
La génération
d’itinéraires alternatifs et leur sélection économique ne sont pas encore raccordées.
Le nom de compilation est NaviSmart et son identifiant de base est `app.navismart`.
L’APK F-Droid de développement est `app.navismart.fdroid.debug`.

## Ancienneté et hypothèses

`Profile.forTrip` applique une durée maximale du stock fournie par l’appelant.
Le simulateur utilise 24 heures : un stock ancien, futur ou non daté devient inconnu.
Une observation encore récente peut néanmoins être fausse après un autre déplacement ;
l’utilisateur doit actualiser son niveau avant le trajet. Aucun suivi automatique des
consommations entre deux voyages n’est livré dans cette phase.

Les hypothèses manuelles non datées restent identifiées comme telles. Pour les
observations datées déjà présentes dans un profil, le garde-fou initial conserve au
maximum 24 heures pour le prix et 365 jours pour la consommation. Ces seuils conservateurs
ne constituent pas une calibration. Les futurs connecteurs appliqueront les durées
propres aux sources gouvernementales avant de fournir des observations au moteur.
L’API `Validity` permet à un adaptateur de configurer séparément les trois durées.
Une observation périmée n’est jamais renouvelée à la date de lecture du fichier.

Le format `ProfileCodec` rejette versions inconnues, champs obligatoires absents,
doublons, motorisations incompatibles et quantités invalides. Il préserve les sources,
dates, unités et intervalles à la sauvegarde. Il ne contient aucun secret ou accès réseau.

## Exemple reproductible

Depuis la racine du dépôt, avec un JDK 17 ou supérieur :

```powershell
./tools/navismart/run-economy.ps1 -Mode Test
./tools/navismart/run-economy.ps1 -Mode Simulate -Scenario ./navismart/economy/scenarios/two-routes.properties -Profiles ./navismart/economy/scenarios/vehicle-profiles.properties -Vehicle all -At '2026-10-13T12:00:00Z'
```

Les trois véhicules et tous leurs chiffres sont fictifs. L’horloge fixe permet de
rejouer le scénario sans transformer artificiellement un ancien stock en donnée fraîche.
Omettre `-Vehicle` utilise le profil actif ; fournir son identifiant en sélectionne un.
L’option `-At` est réservée aux simulations, pas à la navigation réelle.

Sur ces entrées, le trajet sans péage coûte environ 10,8 € en diesel, 10,6 € en flexfuel
et 5,4 € en électrique. Cela n’est pas une comparaison de véhicules réels.

## Validation

39 scénarios économiques et 20 scénarios de profils sont exécutés en Java 17 et via Gradle.
Le stockage Android et l’adaptateur de trajet possèdent dix tests supplémentaires
avec les dépendances JUnit/Mockito existantes.
Le workflow `NaviSmart Android` configure les ressources natives de CoMaps, vérifie
le module et compile un APK arm64 de développement. La [construction est réussie](https://github.com/Seifkone/navismart/actions/runs/38463027554),
ainsi que les dix tests Android. Les métadonnées et la signature de l’APK téléchargé
sont vérifiées dans `validation.md`. Les essais sur appareil restent à effectuer.
La Kia Rio et son boîtier eFlexFuel ne sont pas calibrés par cette livraison.
