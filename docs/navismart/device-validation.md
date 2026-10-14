# Préparation des essais sur appareil

## Matériel de test

Le modèle du téléphone, sa version Android et la référence de l’adaptateur utilisé
seront consignés dans les notes locales des essais, hors du dépôt public.
Les PID réellement accessibles et la stabilité de la liaison devront être identifiés
sur le matériel réel. La première implémentation utilise le service série standard
RFCOMM/SPP de Bluetooth classique ; elle ne devine pas de caractéristiques BLE.

## Essais du jalon Android

Ces essais restent à exécuter sur le téléphone ; ils ne sont pas déclarés réussis :

1. Installer l’APK de développement NaviSmart et vérifier son lancement.
2. Ouvrir Réglages → NaviSmart · Véhicules ; créer un profil sans valeurs numériques.
   Vérifier que les valeurs manquantes restent vides et sont signalées comme inconnues.
3. Saisir consommation, intervalle et prix réellement choisis pour la simulation.
   Renseigner capacité, réserve et niveau uniquement si connus ; confirmer les niveaux.
4. Fermer puis rouvrir l’application ; vérifier le profil et sa sélection active.
5. Créer un second profil et vérifier que la modification du premier ne change pas le second.
6. Télécharger les cartes nécessaires, préparer un trajet automobile et ouvrir l’aperçu
   énergétique avant de commencer le guidage. Vérifier nom du véhicule, état des données
   et réserve. Le coût affiché représente l’énergie consommée, pas les achats ou péages.
7. Après téléchargement des cartes, refaire le calcul et l’aperçu sans réseau.
8. Vérifier les cas inconnus et invalides : consommation vide, bornes inversées, stock
   supérieur à la capacité, changement de carburant non confirmé, ancien niveau d’énergie.

L’aperçu récent préfère les distances routières numériques de chaque étape. Un
affichage approximatif ne doit pas être interprété comme une mesure de consommation.

## Stations et comparaison

1. Télécharger les prix dans Réglages → NaviSmart · Carburants. Vérifier carburant,
   date du prix, état de fraîcheur, rupture éventuelle et position sur la carte.
2. Réouvrir sans réseau : le cache doit rester disponible et conserver les dates.
3. Préparer un trajet sans étape, ajouter une station, attendre le recalcul et revenir
   à la comparaison. Utiliser un profil à un seul réservoir du même carburant.
4. Saisir litres achetés, durée et frais. Vérifier que frais vides produisent un coût
   total inconnu ; zéro doit être saisi explicitement. Vérifier réserve avant station,
   capacité après achat, stock final et limite de temps.
5. Changer le trajet : la comparaison précédente doit être invalidée. Un achat simulé
   ne doit pas modifier le niveau enregistré du véhicule.

## Avis routiers et sauvegardes

Télécharger les avis Bison Futé, vérifier publication, horaires et distinction entre
avis futur et avis listé. Sans cache ou sans avis à proximité, l’écran ne doit pas
annoncer une circulation fluide. Le calcul CoMaps n’est pas modifié par ces avis.

Exporter les profils puis restaurer le fichier après confirmation. Vérifier les
dates de niveaux, le véhicule actif et les unités. Annuler un remplacement doit
laisser les profils en place. Un fichier invalide doit être refusé sans effacement.

## Première acquisition OBD-II

L’écran Diagnostic OBD inclut la connexion Bluetooth classique aux appareils déjà
associés dans Android. Fermer l’autre application OBD, sélectionner la prise puis
vérifier les capteurs annoncés. L’absence de donnée reste distincte d’une valeur
nulle. Arrêter doit fermer la connexion ; les dernières valeurs doivent être
signalées comme historiques. Vérifier ensuite que la collecte continue
pendant le guidage et écran éteint, qu’une notification permet l’arrêt et que la
fermeture de la tâche ferme la connexion. Un redémarrage ne doit pas reconnecter
automatiquement la prise.

Exporter une session CSV, vérifier unités, horodatages et états, puis confirmer que
les fichiers restent privés tant qu’aucun export n’est choisi. Une suppression
demande confirmation. Ces opérations n’effacent aucun défaut et ne codent pas le véhicule.

Les premiers essais devront établir la stabilité de la connexion, le protocole de
réponse, les unités et la fréquence de chaque mesure. L’absence d’un PID ne vaudra
pas zéro. La teneur en éthanol, la consommation réelle, le rapport engagé ou les données
du boîtier eFlexFuel ne sont pas garantis par la compatibilité OBD annoncée.

Les sessions de calibration et de validation seront distinctes. Aucun modèle de
décélération ou conseil précis de rétrogradage ne sera activé à partir de la seule
compatibilité annoncée d’un adaptateur. Les enregistrements réels resteront hors du dépôt public.
