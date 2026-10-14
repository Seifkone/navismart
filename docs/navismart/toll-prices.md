# Prix des péages

Demande ajoutée le 14 octobre 2026 : inclure les péages dans le coût des
itinéraires et des détours pour ravitaillement, avec calcul local.

## État de l’intégration

Le moteur économique accepte déjà un montant de péage par itinéraire. Il le
compte dans le coût économique et dans la dépense immédiate, sans le confondre
avec le carburant acheté. Un montant absent rend le total inconnu ; il ne devient
pas zéro. L’écran de comparaison d’un arrêt permet actuellement de saisir les
frais totaux des deux variantes. La récupération automatique des tarifs et leur
appariement aux routes ne sont pas encore implémentés.

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
