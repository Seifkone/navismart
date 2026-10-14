# Avis routiers gouvernementaux

Réglages → NaviSmart · Avis routiers publics télécharge le flux DATEX II v2
`https://tipi.bison-fute.gouv.fr/bison-fute-ouvert/publicationsDIR/Evenementiel-DIR/grt/RRN/content.xml`.
Il s’agit de l’agrégation horaire référencée par le
[Point d’accès national](https://transport.data.gouv.fr/datasets/evenements-routiers-sur-le-reseau-routier-national-non-concede),
sous Licence Ouverte 2.0. La couverture vise le réseau national non concédé.

Le téléchargement national ne contient aucune position utilisateur. Le cache privé
est remplacé après validation seulement. Cadence maximale : une fois par heure,
temporisation de deux minutes après échec. Publication et téléchargement ont des
dates distinctes ; au-delà de deux heures ou si une date est future, un avertissement
indique que le contenu ne peut plus être considéré comme actuel.

Le parseur indépendant `GovernmentTrafficReader` prend en charge l’enveloppe SOAP
réelle et les espaces de noms DATEX II. Il borne taille, profondeur, champs et
nombre de fiches ; les DTD, entités externes, identifiants dupliqués et versions
incompatibles sont refusés. Les coordonnées, sens déclarés, commentaires publics,
dates de mise à jour et périodes sont conservés. Les horaires récurrents ou complexes
restent à vérifier, les fiches suspendues ou expirées sont exclues de la liste.

La liste affiche au plus quarante avis à moins de cinquante kilomètres du départ
préparé, sinon du centre de carte, selon leurs points déclarés. Elle ne vérifie pas
l’intégralité du trajet, la chaussée ou la voie. Le segment entre deux coordonnées
n’est pas supposé suivre la route. Il n’y a aucune traduction automatique d’un avis
en retard chiffré, fermeture du graphe ou déviation.

Les commentaires du producteur peuvent limiter une mesure à certains véhicules ou
horaires ; ils restent affichés. Un avis peut être localisé sur la carte. L’absence
d’avis et les zones non couvertes signifient trafic inconnu. Les vitesses mesurées,
le référentiel des capteurs, l’appariement au réseau CoMaps et les retards calibrés
restent à implémenter avant un routage tenant compte du trafic.

## Vérification du flux réel

Le 14 octobre 2026, le fichier de publication `2026-10-14T15:57:47.496Z` a été
lu localement : 751 fiches avec coordonnées, dont 479 dans leur période déclarée,
150 futures, 59 expirées, huit suspendues et 55 de validité complexe ou inconnue.
Ces chiffres décrivent ce téléchargement ; ce ne sont pas des constantes produit.
Le fichier national brut reste hors du dépôt.
