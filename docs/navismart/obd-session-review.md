# Analyse locale des sessions OBD

L’outil `tools/navismart/analyze_obd.py` lit uniquement les CSV NaviSmart de schéma 1.
Il utilise Python 3.11 ou supérieur et sa bibliothèque standard, sans service distant
ni installation de dépendance. Les CSV et rapports réels restent hors du dépôt public.

Après export d’une session sur le téléphone puis copie sur l’ordinateur :

```powershell
python -X utf8 tools/navismart/analyze_obd.py C:/mes-mesures/session.csv --report C:/mes-mesures/qualite.json
```

Le rapport indique, par capteur : unité, nombre de réponses disponibles, absentes,
invalides ou contradictoires, extrêmes, intervalle médian et plus grand intervalle.
Il vérifie les PID, unités, valeurs finies, plages standards et temps monotoniques.
Les ruptures de l’horloge UTC sont signalées sans remplacer le temps monotone.

Pour la vitesse et le débit de carburant déclaré seulement, il calcule une intégrale
trapézoïdale nominale sur les paires de mesures disponibles consécutives. Une valeur
manquante ou un intervalle de plus de trois secondes interrompt la couverture. Le
paramètre `--max-gap-seconds` rend cette hypothèse explicite et configurable.

Le rapport donne durée couverte et fraction du temps observé. Un débit mesuré nul
reste nul ; aucune paire utilisable donne une intégrale inconnue, pas zéro. Les
couvertures de vitesse et carburant sont séparées : leur ratio ne devient pas une
consommation moyenne du trajet. Il n’y a aucune conversion MAF/carburant, estimation
de litres à partir d’un pourcentage de réservoir ou correction E85 inventée.

## Séparation avant calibration et validation

```powershell
python -X utf8 tools/navismart/analyze_obd.py --calibration C:/mes-mesures/calibration.csv --validation C:/mes-mesures/validation.csv --report C:/mes-mesures/separation.json
```

Les contrôles comparent les empreintes des fichiers, les périodes UTC et la stabilité
de l’horloge. Le même fichier, des périodes superposées ou des sauts d’horloge ne
passent pas le contrôle. Des fichiers distincts et non superposés ne prouvent pas
l’indépendance physique des conditions de conduite ; le rapport ne prétend ni avoir
ajusté un modèle de décélération ni avoir validé une calibration du véhicule.

Le programme refuse d’écraser une source ou un rapport déjà présent. Les entrées
malformées, trop grandes et les versions non reconnues sont refusées. Un premier
essai des tests locaux n’avait pas accès au répertoire temporaire du bac à sable
Windows ; les tests sont réussis en utilisant le répertoire de travail autorisé.

Tests reproductibles :

```powershell
python -m unittest discover -s tools/navismart/tests -p 'test_*.py' -v
```

Si le répertoire temporaire système est inaccessible, définir `NAVISMART_TEST_TMPDIR`
sur un dossier de travail autorisé existant avant la commande. Les tests utilisent
uniquement des données synthétiques et nettoient leurs propres fichiers temporaires.
