# Sources et faisabilité initiale

Inventaire vérifié le 13 octobre 2026. Les fournisseurs restent à raccorder à l’application.

## Navigation autonome

[CoMaps](https://github.com/comaps/comaps) : code sous Apache 2.0 ; moteur cartographique,
recherche et routage embarqués. Conserver `LICENSE`, `NOTICE` et les mentions des
dépendances. Les données cartographiques OSM ont leurs propres obligations d’attribution.
Le téléchargement des cartes reste nécessaire avant une navigation entièrement hors ligne.

La dernière modification amont a changé trois URL de sous-modules en chemins relatifs
qui pointent dans un fork vers des dépôts inexistants. La branche NaviSmart rétablit leurs
URL officielles Codeberg précédentes pour que la préparation native soit reproductible.

## Prix des carburants

Source primaire : [données publiques du site gouvernemental](https://www.prix-carburants.gouv.fr/index.php/rubrique/opendata/).
Flux instantané : `https://donnees.roulez-eco.fr/opendata/instantane`.
Archive ZIP/XML téléchargeable ; données libres et gratuites sous Licence Ouverte.
La page indique un flux de moins de dix minutes, mais chaque prix porte sa propre date.
La date de téléchargement ne doit pas remplacer la date de déclaration du prix.

Choix prévu : télécharger le flux national, stocker un cache local et chercher les stations
sur le téléphone. Cela évite d’envoyer les coordonnées du trajet à un service de recherche.
Respecter la cadence de publication et appliquer une politique explicite de fraîcheur.
Une rupture, une absence de prix ou un prix ancien ne doit pas devenir un prix nul.
Les informations d’enseigne ne seront pas complétées via une API commerciale tierce.

## Circulation et événements

[Mesures de circulation](https://transport.data.gouv.fr/datasets/etat-de-circulation-en-temps-reel-sur-le-reseau-national-routier-non-concede)
et [événements routiers](https://transport.data.gouv.fr/datasets/evenements-routiers-sur-le-reseau-routier-national-non-concede).
Producteur public, Licence Ouverte 2.0. Flux XML/DATEX II et référentiels associés.
La couverture concerne principalement le réseau national non concédé ; elle est partielle.
Le flux d’événements courants et son agrégation ont des cadences différentes.

Avant intégration : enregistrer l’URL exacte de chaque ressource, sa couverture, son sens
de circulation, ses horaires et sa cadence. Vérifier le géoréférencement, les fermetures,
les doublons, les événements expirés et les changements de version DATEX II avec des
fichiers représentatifs. Aucune durée de bouchon ne sera inventée à partir d’un événement
qui ne fournit pas assez d’informations.

## Données encore manquantes

Tarifs de péages par trajet, tarifs dynamiques de recharge et disponibilité des bornes,
paramètres OBD réellement accessibles, rapports de transmission, régime maximal et jeux
de conduite indépendants ne sont pas encore acquis. Le simulateur accepte des valeurs
explicites ou leur absence ; il ne garantit aucune couverture pour ces données.

Des ressources publiques peuvent avoir des contraintes d’accès ou subir des indisponibilités.
Le projet conserve son calcul local et ses données en cache ; aucune gratuité de serveur
ni disponibilité illimitée n’est supposée.
