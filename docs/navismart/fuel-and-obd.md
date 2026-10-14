# Stations gouvernementales et acquisition OBD

## Stations

Réglages → NaviSmart · Carburants : téléchargement explicite de l’archive nationale
`https://donnees.roulez-eco.fr/opendata/instantane_ruptures`. La recherche géographique
est locale et aucune coordonnée du véhicule n’est envoyée au producteur.

Le cache privé est remplacé atomiquement après validation XML. Un échec garde la
dernière archive valide. La cadence minimale est dix minutes, avec deux minutes
de temporisation après une tentative échouée. Les prix conservent leur date de
déclaration ; leur import ne les rajeunit pas. Le seuil d’affichage est sept jours,
celui des informations de rupture trente minutes. Une disponibilité déclarée ne
garantit ni ouverture réelle ni présence de carburant à l’arrivée.

Le parseur respecte l’encodage XML, les coordonnées gouvernementales divisées par
100 000 et les prix déjà exprimés en euros par litre. Il refuse les DTD, entités
externes, doublons d’identifiant et documents hors limites. Les dates ambiguës au
changement d’heure et les champs absents restent inconnus.

La liste présente au plus trente stations dans un rayon de trente kilomètres du
départ préparé, sinon du centre de la carte. La distance affichée est à vol d’oiseau.
Chaque station peut être affichée sur la carte ou ajoutée comme étape à un trajet
automobile préparé ; CoMaps recalcule alors l’itinéraire localement. Cela ne garantit
pas un détour rentable. Les comparaisons économiques ont besoin des vrais tronçons
routiers, du stock, des quantités achetées et des frais renseignés.

`RefuelPlanner` prépare déjà cette comparaison avec des tronçons et quantités
explicites. Il délègue les réserves chronologiques, la capacité et le budget temps
au moteur économique. Aucun calcul à vol d’oiseau ne remplace le routage.

## OBD standard par Bluetooth classique

Réglages → NaviSmart · Diagnostic OBD : choisir un appareil déjà associé dans
Android. La liaison utilise RFCOMM/SPP ; la découverte radio et les profils BLE
propriétaires ne sont pas implémentés. Fermer les autres applications utilisant
la prise. Android 12 et suivants demandent l’autorisation de connexion Bluetooth.

Une liste finie de commandes configure l’adaptateur ELM et interroge uniquement
le Mode 01 standard. Aucun effacement de défaut, écriture calculateur, VIN ou
commande constructeur n’est proposé. Les masques de disponibilité déterminent
les capteurs interrogés. Des réponses contradictoires de calculateurs ne sont pas
arbitrées ; elles deviennent ambiguës.

Les mesures couvrent, si annoncées : régime, vitesse, températures, charge,
papillon, pression d’admission, débit d’air, corrections carburant banc 1, niveau
du réservoir, éthanol et débit de carburant déclaré. Un pourcentage de réservoir
n’est pas converti en litres. Le débit d’air n’est pas converti en consommation
par un rapport air/carburant fixe. Un débit déclaré n’est pas une calibration
physique validée.

La session initiale est réservée à l’écran de diagnostic : elle s’arrête en le
quittant, en cas d’erreur, après une heure ou cinq Mio. Au maximum cent sessions
et cinquante Mio sont conservés sans suppression automatique. Chaque CSV privé
contient les horodatages UTC et monotoniques individuels, PID, unité et état.
L’export utilise le sélecteur de destination Android ; une suppression demande
confirmation. Les règles de sauvegarde existantes excluent ces fichiers privés.

Cette version prépare l’acquisition et les essais de compatibilité. Elle n’atteste
aucun essai sur véhicule et n’émet pas encore de conseil de décélération ou de
rétrogradage. La collecte pendant le guidage et les profils constructeur restent
à ajouter après validation de cette connexion.

## Références techniques

- [Données gouvernementales](https://www.prix-carburants.gouv.fr/index.php/rubrique/opendata/) : Licence Ouverte et dates de déclaration.
- [ELM327, documentation du fabricant](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf) : dialogue, invite, Mode 01 et exemples régime/température.
- [Android, connexion Bluetooth](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices) et [autorisations](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- [OBDLink, table des PID](https://www.obdlink.com/obd-apps/obdlink-app/) et [python-OBD, tables documentées](https://python-obd.readthedocs.io/en/latest/Command%20Tables/) : vérification croisée des identifiants et unités, sans dépendance ni reprise de code.
