# Intégrer les évolutions de CoMaps

Le fork conserve l’origine CoMaps dans le remote `upstream`, actuellement
`https://github.com/comaps/comaps.git`. La branche de travail est `navismart` et
ses publications vont vers `origin` (`Seifkone/navismart`). Un fork ne fusionne pas
automatiquement les nouvelles modifications du projet d’origine.

Depuis un arbre de travail propre, examiner puis intégrer les changements :

```powershell
git switch navismart
git status --short
git fetch upstream
git log --oneline HEAD..upstream/main
git merge upstream/main
git submodule sync --recursive
git submodule update --init --recursive
```

Les changements locaux en cours doivent d’abord être conservés dans un commit ou
une sauvegarde appropriée. Résoudre les conflits en conservant les modules et
adaptateurs NaviSmart, puis relire le résultat. Les mises à jour ne nécessitent pas
de réécrire l’historique public du fork.

Points d’intégration actuellement spécifiques :

- `navismart/economy` : moteur, profils, sources publiques et protocole OBD sans Android.
- `android/app/.../navismart` et les écrans de réglages : adaptateurs de plateforme.
- `Framework.nativeGetRouteLegMetrics` et son petit pont JNI : métriques routières
  numériques par étape, sans modification de l’algorithme de routage CoMaps.
- `app.navismart`, les ressources propres et la clé publique de développement existante.
- `.gitmodules` : vérifier que les URL restent valides pour un fork, notamment les
  dépendances dont les URL relatives pointaient vers des dépôts inexistants.

Vérifications avant publication :

```powershell
./tools/navismart/run-economy.ps1 -Mode Test
python -m unittest discover -s tools/navismart/tests -p 'test_*.py' -v
git diff --check
git push origin navismart
```

Le workflow Android vérifie ensuite le module, les adaptateurs et l’assemblage arm64.
Après une évolution du routeur ou du cycle Android, reprendre aussi les
[essais sur appareil](device-validation.md). La validation d’un ancien APK ne valide
pas automatiquement le nouveau code amont.
