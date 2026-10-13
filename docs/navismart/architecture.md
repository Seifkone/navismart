# Architecture et décisions NaviSmart

## Référence

Cahier des charges v2.1, sections 20–24, récupéré le 13 octobre 2026.
La première mission est le simulateur économique autonome. Le code ci-joint couvre
cette mission et les modèles initiaux des profils véhicule ; il ne valide pas les
phases nécessitant des données réelles ou un véhicule.

Les décisions prises ensuite par l’utilisateur précisent cette référence :

- NaviSmart sera développé comme un fork de CoMaps, avec cartes et routage embarqués.
- Aucun appel à Valhalla, OSRM, GraphHopper ou une autre API de routage externe n’est requis.
- Les échanges opérationnels supplémentaires pour prix et trafic utiliseront les sources gouvernementales.
- Le dépôt retenu est le fork public `Seifkone/navismart`, avec `main` et `navismart`.
  Ce choix remplace la mention d’un dépôt privé dans l’ancienne spécification.
- Les trajets et la télémétrie ne seront pas publiés dans ce dépôt.

## Modules

`navismart/economy` est une bibliothèque Java 17 sans dépendance à Android ni au réseau.
Son API accepte un profil véhicule, des trajets candidats et la politique de choix.
Le simulateur en console utilise la même API. Le SDK CoMaps pourra l’appeler plus tard.

`TripModel` porte les profils, les réserves d’énergie, les tronçons et les achats.
`Estimate` porte les valeurs et leurs intervalles, leur état, leur source et leur date.
`EconomyEngine` produit les coûts, la faisabilité et une décision expliquée.
`DataFreshness` applique une durée de validité fournie par la source avec une horloge injectée.
`ScenarioReader` lit un format de laboratoire UTF-8 configurable.

Les adaptateurs futurs restent extérieurs à cette bibliothèque : routage natif
CoMaps, import des données gouvernementales, stockage Android et OBD-II en lecture seule.
Le téléchargement initial des cartes et de leurs mises à jour est distinct du calcul
d’itinéraire : une fois les régions présentes sur le téléphone, le calcul reste local.

## Unités et coût

Distances en kilomètres ; temps en minutes ; montants en euros.
Les réserves thermiques sont en litres, les réserves électriques en kWh utilisables.
Chaque consommation est exprimée par 100 km pour son réservoir ou sa batterie.
Chaque prix est exprimé par unité de l’énergie correspondante.

Les achats et la conduite sont ordonnés dans une chronologie. Un achat fait après un
tronçon ne peut pas rendre ce tronçon accessible rétroactivement. Le stock doit rester
au-dessus de la réserve à chaque étape et ne pas dépasser sa capacité lors des achats.
Un résultat négatif reste visible comme une violation ; il n’est pas corrigé en zéro.

Le coût de l’énergie consommée utilise un inventaire FIFO : énergie initiale au prix
de référence renseigné, puis lots achetés au prix de chaque arrêt. L’énergie achetée
mais restant à l’arrivée est un stock, pas une consommation de ce trajet.

Le coût économique est : énergie consommée + péages du trajet + autres frais + frais
fixes des arrêts. La dépense immédiate est : achats énergétiques + ces mêmes frais.
Ces deux vues ne sont jamais additionnées. Le temps n’est pas converti en euros.

Pour l’électricité, un achat distingue kWh achetés et kWh réellement stockés.
Le prix par unité stockée inclut ainsi les pertes fournies en entrée. Les durées des
arrêts sont des estimations explicites ; elles ne sont pas calculées à partir de la
seule puissance maximale d’une borne.

Les hybrides demandent une répartition énergétique explicite pour chaque tronçon.
Le moteur n’applique pas simultanément deux consommations de traction complètes.
Le flexfuel emploie la consommation mesurée renseignée, sans multiplicateur arbitraire.

## Incertitude, temps et stabilité

Une valeur absente vaut `null`, jamais zéro. Une valeur centrale sans bornes signifie
que son incertitude n’est pas quantifiée. Les exemples exacts sont des entrées de tests
explicitement synthétiques ; une estimation réelle ne devient pas exacte par défaut.

Le trajet de référence est le plus rapide parmi les candidats physiquement possibles
dont la durée est exploitable. La limite centrale est `durée × (1 + pourcentage / 100)`.
Les minutes affichées sont l’équivalent de ce pourcentage, pas un second retard ajouté.
Les temps de ravitaillement et tous les détours doivent déjà figurer dans le trajet.

Pour une comparaison conservatrice, la borne haute de durée du candidat doit rester
sous la limite calculée avec la borne basse de référence. Les dépendances statistiques
entre durées ne sont pas supposées : cette méthode peut conserver une route par prudence.

Un changement doit présenter un gain supérieur au seuil utilisateur et à la pénalité
de changement, y compris avec les bornes défavorables des coûts. En cas de recouvrement
des intervalles, le trajet courant ou de référence est conservé et la raison est donnée.
La temporisation et la gestion des mises à jour trafic appartiendront à l’adaptateur de navigation.

Si des candidats possibles ont un coût inconnu, le résultat indique que le meilleur
trajet global ne peut pas être établi. Les valeurs périmées peuvent être affichées
pour diagnostic, mais ne permettent pas une recommandation définitive.

## Réutilisation de l’ancien NaviSmart

Conservé : configuration consommation/capacité/niveau/réserve, comparaison des coûts,
prix adaptés au carburant, prise en compte des détours et montants à acheter.

Corrigé : échec réseau distinct de zéro, péages propres à chaque trajet, stock non
accessible à une station non atteignable, calcul énergétique distinct du montant payé,
validation des capacités et absence de coefficient arbitraire pour l’éthanol.

Les anciens fichiers Kotlin et leur clé commerciale restent hors du fork publié.

## Risques et étapes restantes

1. CoMaps ne livre pas automatiquement l’optimisation économique : il faudra produire
   plusieurs candidats et obtenir les distances réelles de chaque détour via son moteur natif.
2. Le trafic public n’est pas exhaustif. Son absence doit rester `UNKNOWN`, et les
   perturbations doivent être associées au bon segment et au bon sens de circulation.
3. Les tarifs des péages ne sont pas contenus automatiquement dans une carte OSM.
   Un montant manquant reste inconnu, ou doit être renseigné explicitement par l’utilisateur.
4. La consommation moyenne est un premier modèle. La vitesse, la pente, les arrêts et
   l’énergie réelle nécessitent des modèles et données validés avant d’annoncer un gain fiable.
5. Les données de la Kia doivent être obtenues puis séparées par sessions pour calibration
   et validation. Aucun coefficient de décélération n’a été inventé à partir du seul exemple à 80 km/h.
6. La compilation Android nécessite la chaîne native CoMaps, notamment NDK 28.2,
   CMake et Java 21. La validation du module Java n’atteste pas la compilation de l’APK.

Le prochain raccordement logiciel sera un adaptateur des trajets CoMaps et un écran de
profils véhicule, suivi des connecteurs gouvernementaux avec cache local. L’OBD-II et
les conseils de rétrogradage resteront conditionnés aux données et essais réels.
