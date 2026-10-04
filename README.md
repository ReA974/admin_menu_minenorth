# Panneau Admin MineNorthRP (`minenorth_admin`)

Forge 1.20.1. Commande **`/mnadmin`** : un seul écran pour gérer les joueurs (en ligne **et hors ligne**).

## Compilation
1. Les jars des autres mods sont déjà dans `libs/` (eurobank 1.0.0, permis 1.3.0, rp_vehicles 1.0.0).
   Si tu changes une version, mets à jour `gradle.properties`.
2. `./gradlew build` → `build/libs/minenorth_admin-1.0.0.jar` (serveur ET clients).

Les trois mods sont **optionnels** : un onglet n'apparaît que si son mod est installé.

## Onglets
- **Joueurs** : recherche, fiche joueur, TP vers / TP ici.
  - Banque : solde (définir / ajouter / retirer), ouvrir un compte, banquier, carte bancaire, annuler une dette / refuser une demande de prêt.
  - Permis : donner (durée en jours, vide = config, 0 = permanent), retirer, nouvelle carte, points, effacer les délais d'examen.
  - Garage : garage et fourrière ; supprimer, libérer de la fourrière, transférer à un autre joueur.
  - Inventaire + ender chest : copier, prendre, supprimer, tout vider, donner l'objet en main. Hors ligne : modifie le fichier `playerdata`.
- **Supprimer un joueur** (bouton rouge dans sa fiche, double clic, joueur déconnecté) : efface TOUTES ses données —
  fichiers du monde (inventaire, ender chest, position, succès, stats), compte bancaire et prêts, permis, garage et
  fourrière, rôle staff. S'il revient, il repart de zéro. Permission « Supprimer un joueur » (propriétaire et Gérant par défaut).
- **Staff** : rôles (nom, rang, permissions) et membres. Invisible pour les joueurs : `/mnadmin` n'existe même pas pour eux.
- **Journal** : toutes les actions, aussi écrites dans `logs/minenorth_admin.log`.

## Rôles et rangs
- Op niveau 4 (configurable) = **propriétaire** : tous les droits, sans rôle.
- Rôles par défaut : Modérateur (10), Admin (50), Gérant (90). Modifiables / supprimables.
- On ne gère que les rôles et membres de **rang inférieur** au sien, on ne donne que les permissions qu'on a,
  et on ne peut pas agir sur un staff de rang supérieur ou égal.

## Config
`world/serverconfig/minenorth_admin-server.toml` : niveau d'op propriétaire, taille du journal, fichier de log,
prévenir le joueur quand le staff modifie sa banque / ses permis.
