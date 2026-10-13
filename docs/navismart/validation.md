# Validation des jalons NaviSmart

Validation locale le 13 octobre 2026, Java 17 et Gradle 8.14.4.

- Compilation Java indépendante : réussie.
- 39 scénarios économiques et 20 scénarios de profils : réussis ; résultats dans `acceptance-results.txt`.
- Compilation et tâche `check` du projet Gradle indépendant : réussies.
- Simulation intégrée et simulation configurable UTF-8 : exécutées.
- Comparaison de trois profils synthétiques enregistrés : exécutée avec une horloge fixe.
- Workflow GitHub du moteur et des profils : réussi sur le commit `34b8cd3`.
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
