# Prix des péages

Demande ajoutée le 14 octobre 2026 : inclure les péages dans le coût des
itinéraires et des détours pour ravitaillement, avec calcul local.

## État de l’intégration

Le moteur économique accepte déjà un montant de péage par itinéraire. Il le
compte dans le coût économique et dans la dépense immédiate, sans le confondre
avec le carburant acheté. Un montant absent rend le total inconnu ; il ne devient
pas zéro. L’écran de comparaison d’un arrêt permet actuellement de saisir les
frais totaux des deux variantes.

Un écran « Comparer le coût des trajets » est maintenant accessible depuis le
panneau du trajet automobile et les réglages. Il conserve la variante standard
et celle calculée avec évitement des péages, à partir des distances et durées
réelles du moteur CoMaps. Il affiche énergie consommée, péages, total estimé,
état des données et réserve. Choisir une variante recalcule son parcours avant
le guidage, en conservant les étapes intermédiaires.

Le catalogue embarqué contient 21 505 couples orientés APRR et 955 AREA,
avec cinq classes tarifaires et une date d’application explicite. La conversion
vient directement des colonnes des PDF officiels. Le CSV communautaire APRR
a été rejeté : ses noms fusionnés créaient des milliers de fausses sorties.
L’extraction communautaire AREA comportait aussi un code fantôme `410` et un nom
tronqué ; la conversion officielle retrouve 177 identifiants APRR et 51 AREA,
y compris les libellés spéciaux présents dans les tableaux.

Le référentiel gouvernemental apporte 1 266 positions, converties de Lambert-93
en WGS84. Elles servent à proposer des entrées et sorties proches de la géométrie
routière, uniquement si les noms correspondent sans ambiguïté et si un tarif
orienté existe. Une suggestion géographique n’établit pas la chaussée, le sens
réel ou la couverture de tous les péages : l’utilisateur confirme les passages
et la couverture complète avant de les compter comme total. Le choix manuel
entrée/sortie et la saisie d’un total connu restent disponibles.

Les tarifs des autres réseaux ne sont pas encore convertis. L’accès automatisé
Sanef a été refusé pendant la recherche. Les TXT publics VINCI ne donnent pas
une date d’application explicite dans leurs lignes ; leur simple date HTTP ne
suffit pas à certifier le barème. Aucun montant national complet n’est inventé.

## Actualisation des barèmes

Le workflow `navismart-tariffs.yml` vérifie chaque lundi les liens actuels de la
page officielle APRR/AREA, lit la date d’application des PDF et convertit les
colonnes. Il vérifie les montants, identifiants, doublons, volumes et régressions.
Le premier cycle réel a [réussi](https://github.com/Seifkone/navismart/actions/runs/38607410757).
La branche par défaut du fork est `navismart`, ce qui rend cette programmation
applicable. La branche `main` d’origine est conservée.

Les fichiers contrôlés sont publiés dans le fork avec un manifeste et des
empreintes SHA-256. L’application télécharge ces fichiers statiques sans envoyer
le trajet, la position ou le véhicule. Ce n’est pas une API de calcul distante.
Le calcul et le catalogue embarqué restent utilisables hors connexion.

L’écran recherche une actualisation quand le catalogue contrôlé a plus de sept
jours ; un bouton permet aussi une demande explicite, avec cinq minutes entre
tentatives. Une version future, ancienne, incomplète, corrompue ou avec un schéma
incompatible est refusée. Les données de toutes les sources sont validées avant
remplacement atomique du cache. La date de contrôle ne remplace pas la date
d’application du tarif. Un ancien millésime reste signalé ancien, même retéléchargé.

Une publication officielle dans un nouveau format peut nécessiter d’adapter le
convertisseur ; dans ce cas le traitement échoue et conserve les données précédentes.
Les indisponibilités du serveur ou de GitHub ne rendent pas le calcul local distant.

## Utilisation de la comparaison

1. Créer et sélectionner un véhicule, avec consommation et prix de l’énergie connus.
   Renseigner capacité, niveau, réserve et dates lorsque disponibles.
2. Préparer un trajet automobile, puis ouvrir « Comparer le coût des trajets ».
3. Choisir la classe de péage du véhicule. Vérifier les suggestions ou choisir
   les entrées et sorties ; confirmer si elles couvrent réellement tout le trajet.
4. Conserver cette variante, puis calculer celle avec évitement des péages.
5. Comparer les durées et totaux. Une route sans section à péage marquée reçoit
   une estimation à zéro dépendant de l’exhaustivité de la carte. Un ferry non
   chiffré laisse le total inconnu. Il n’y a aucune hypothèse de stationnement gratuit.
6. Toucher la variante souhaitée pour la recalculer ; revenir à la carte pour
   vérifier et démarrer le guidage.

Les variantes et prix saisis sont temporaires : changement de points, classe,
véhicule ou autres options, horloge incohérente et expiration après 30 minutes
les invalident. Les profils modifiés sont réévalués avec leurs valeurs actuelles.
L’achat de carburant et le coût du carburant consommé ne sont pas additionnés.

## Sources examinées

- [APRR/AREA : barèmes officiels](https://voyage.aprr.fr/tarifs-de-peage) :
  tableaux par classe et entrée/sortie, applicables au 1er février 2026 au moment
  de la consultation. Les systèmes ouverts et le tunnel Maurice-Lemaire ont
  des liens séparés ; le seul tableau entrée/sortie ne couvre donc pas tout.
- [VINCI Autoroutes : grilles officielles](https://www.vinci-autoroutes.com/fr/conseils/autoroute-mode-demploi/tarifs-peage-vinci-autoroutes/) :
  plusieurs concessions et classes ; vérifier la date applicable à chaque fichier.
- [Classes de véhicules ASFA](https://www.autoroutes.fr/fr/classification-des-vehicules.htm) :
  la classe dépend des caractéristiques du véhicule et de l’ensemble roulant.
  Elle ne peut pas être déduite du seul nom de la marque ou de la masse réelle.
- [Gares de péage, données publiques](https://www.data.gouv.fr/datasets/gares-de-peage-du-reseau-routier-national-concede) :
  référentiel géographique utile, mais distinct d’un catalogue de tarifs.
- [CSV APRR/AREA sur data.gouv.fr](https://www.data.gouv.fr/datasets/tarifs-autoroutes-aprr) :
  extraction publiée par un particulier sous Licence Ouverte 2.0, et non jeu
  produit par l’administration. Les ressources comportent plusieurs millésimes
  (2023 à 2026). Leur date de téléchargement ou de contrôle ne prouve pas une
  actualisation du barème. Aucun téléchargement automatique de ces CSV n’est
  ajouté à l’application.

## Solution retenue pour le développement

Conserver des catalogues locaux, issus des barèmes officiels et contrôlés lors
de leur conversion. Le téléphone n’envoie pas les trajets à un service commercial
pour obtenir les montants. Une mise à jour de fichiers de données reste distincte
d’une API distante de calcul d’itinéraire.

Chaque tarif doit identifier : concession, gare ou section, entrée, sortie, sens,
classe, montant TTC en centimes, période applicable, éventuelles conditions
horaires/saisonnières, URL source et date de vérification. Préserver les conditions
particulières des ouvrages et les systèmes ouverts ou en flux libre. Ne pas
supposer que le retour coûte autant que l’aller ni extrapoler un prix au kilomètre.

Avant de produire un total automatique :

1. Lire la géométrie et le sens du parcours réel, puis identifier sans ambiguïté
   les passages et les limites des systèmes de péage.
2. Choisir une classe de péage explicite, en tenant compte d’une remorque si besoin.
3. Chercher le tarif applicable à la date du passage ; conserver les prix absents,
   anciens ou conditionnels avec leur état propre.
4. Recalculer pour chaque variante : une sortie pour faire le plein peut modifier
   le péage. Éviter tout double comptage entre forfait et tarif entrée/sortie.
5. Afficher séparément le sous-total connu et la couverture. Un itinéraire avec
   des sections inconnues n’a pas de total fiable ; zéro exige une absence de
   péage confirmée sur l’ensemble du trajet.

Critères de validation : sens inverse distinct, mauvaise classe, futur barème,
date expirée, section manquante, saison/heure, systèmes successifs, sortie pour
ravitaillement et absence de double comptage. Les cas du moteur « péages inconnus »
et « zéro confirmé » existent déjà ; ils ne valident pas un catalogue national.
