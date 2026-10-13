# Préparation des essais sur appareil

## Matériel de test

Le modèle du téléphone, sa version Android et la référence de l’adaptateur utilisé
seront consignés dans les notes locales des essais, hors du dépôt public.
Le protocole série, les services/caractéristiques Bluetooth et les PID accessibles
devront être identifiés sur le matériel réel, sans imposer des UUID génériques non vérifiés.

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

La distance fournie par le résumé CoMaps est parfois arrondie. Un affichage approximatif
ne doit pas être interprété comme une mesure de consommation du trajet.

## Première acquisition OBD-II

La fonction Bluetooth/OBD n’est pas encore incluse dans cet APK. La phase d’acquisition
devra commencer par une identification de l’adaptateur et une découverte des mesures
réellement disponibles, avec journal de diagnostic local. Elle sera en lecture seule.

Les premiers essais devront établir la stabilité de la connexion, le protocole de
réponse, les unités et la fréquence de chaque mesure. L’absence d’un PID ne vaudra
pas zéro. La teneur en éthanol, la consommation réelle, le rapport engagé ou les données
du boîtier eFlexFuel ne sont pas garantis par la compatibilité OBD annoncée.

Les sessions de calibration et de validation seront distinctes. Aucun modèle de
décélération ou conseil précis de rétrogradage ne sera activé à partir de la seule
compatibilité annoncée d’un adaptateur. Les enregistrements réels resteront hors du dépôt public.
