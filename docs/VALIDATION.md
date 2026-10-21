# Validation réellement exécutée — 21 octobre 2026

## Dernière mise à jour : connexion Meta et coexistence

Les résultats de cette section remplacent les anciens blocages Docker/Android/Embedded Signup décrits plus bas, qui retracent les essais précédents.

- `mvnw.cmd verify -q` : code de sortie 0, 28 tests réussis, aucun échec, aucune erreur et aucun test ignoré. Le test PostgreSQL 17.11 s’est exécuté avec Testcontainers dans une base isolée ; les migrations V1 à V8 ont été validées. JAR compilé.
- Tests ajoutés : chiffrement authentifié/randomisé, lien expiré ou invalide, contrôle administrateur, challenge préalable, échange unique du code, rejet d’une autre application Meta, refus du parcours standard et d’une coexistence non confirmée, identité contrôlée, confirmation explicite, souscription et requêtes uniques, absence de répétition après timeout.
- Import : historique idempotent, progression Meta réelle, absence de fenêtre de service/non-lus artificiels, provenance des échos, aucun consentement implicite, suppression non destructive des contacts, refus d’historique, média reçu avant son message de base.
- `dart format lib test` : 12 fichiers, aucun changement restant ; `flutter analyze --no-pub` : aucune anomalie.
- `flutter test --no-pub` : 11 tests réussis, dont deux tests de l’écran WhatsApp qui distinguent configuration manquante et parcours disponible.
- `node --check backend/src/main/resources/onboarding/signup.js` : code de sortie 0. Le SDK Meta dans un navigateur réel reste à tester avec une configuration autorisée ; une vérification syntaxique ne valide pas OAuth.
- `flutter build apk --debug --no-pub --dart-define=API_BASE_URL=http://127.0.0.1:8080` : réussi. Version de développement avec serveur local USB ; ce fichier ne constitue pas une livraison commerciale signée.
- Installation sur TECNO KI7 par `adb install -r` : Success ; activité MainActivity lancée. Les données de l’application sont conservées.
- Base locale sauvegardée avant mise à jour dans `build/restaurant-before-onboarding.dump`, ignoré par Git ; elle peut contenir des données privées. La clé de chiffrement, lorsqu’elle sera configurée, devra être sauvegardée séparément.
- `docker compose up -d --build --no-deps api` : réussi. API redémarrée, santé UP, base locale à la version Flyway 8, aucun numéro connecté en base.
- Page locale `/connect/whatsapp` : HTTP 200 et Cache-Control no-store ; lien invalide sur `/connect/whatsapp/config` : HTTP 401.

**Non validé :** véritable autorisation Meta, éligibilité coexistence, réception d’un historique réel, maintien du numéro connecté, envoi réel, configuration HTTPS publique, signature Android de distribution. Les médias WhatsApp, les modifications/révocations de messages et l’import d’archives externes restent incomplets. Aucune mise en production commerciale n’est annoncée.

## Environnement

- Flutter 3.47.7 stable, Dart 3.13.5.
- Java 21.0.11 pour Maven ; Gradle Android utilise le JBR 25 fourni avec Android Studio.
- SDK Android détecté, licences acceptées, plateforme android-37.0 et outils 36.0.0.
- Maven 3.9.16 trouvé dans le cache local ; lanceur officiel 3.9.16 ajouté au projet.
- Aucun appareil Android connecté ; aucun essai iOS (hôte Windows).
- Docker CLI présent. Le moteur Linux reste inaccessible, y compris après `docker desktop start --detach` ; aucun conteneur lancé par cette livraison.
- Aucun identifiant externe Meta et aucune connexion réelle testée.

Les commandes Flutter ont utilisé le moteur installé directement (`C:/flutter/bin/cache/dart-sdk/bin/dart.exe C:/flutter/bin/cache/flutter_tools.snapshot ...`), car le lanceur batch ne terminait pas ses premières invocations. Ce contournement utilise bien le Flutter SDK local ; les tests n’ont pas été simulés. L’accès aux caches SDK/Maven a été accordé par le contrôle automatique d’autorisation.

## Résultats

| Vérification | Résultat constaté |
| --- | --- |
| Flutter version et `flutter doctor -v` | Exécutés ; doctor ne signale pas de problème |
| `flutter pub get` | Dépendances téléchargées et verrou mises à jour ; code de sortie 1 à la création des liens de plugins Windows, mode développeur requis. Ne pas compter comme succès complet. |
| `dart format lib test` | Réussi sur les sources et tests finaux |
| `flutter analyze` | Réussi, « No issues found » sur l’exécution finale |
| `flutter test` | **9 tests réussis**, zéro échec |
| `mvn test` puis `mvn verify` | Exécutés ; les défauts rencontrés ont été corrigés. Dernier `verify` : **14 tests réussis, 1 ignoré**, zéro échec/erreur |
| Migration Flyway | Quatre migrations appliquées et validées dans H2 en mode PostgreSQL, en test uniquement |
| Compilation backend | Réussie : `backend/target/restaurant-connect-1.0.0.jar`, archive Spring Boot Java 21 générée |
| PostgreSQL réel Testcontainers | **Non validé**, un test explicitement ignoré faute de moteur Docker |
| Syntaxe Compose | Réussie via `docker compose --env-file .env.example config --no-env-resolution --quiet`, avec mot de passe de validation temporaire dans le processus uniquement ; aucun service lancé |
| Image Docker et HTTPS | Fichiers fournis ; construction/exécution serveur et certificat réel non vérifiés |
| Android release | Échec initial NDK corrigé : NDK 28.2.13676358 installé depuis Google. Nouvelle compilation en échec lors de l’installation réseau de la plateforme Android 36 (« connexion abandonnée par un logiciel de l’hôte »). L’installation explicite de cette plateforme est restée figée et a été interrompue. Aucun APK produit ni succès de compilation déclaré. |
| WhatsApp / Embedded Signup / historique réel | **Non validés** : aucun compte configuré, aucun envoi, aucune synchronisation réelle |

## Couverture des tests

Flutter : accès fermé sans serveur/session, distinction des statuts réels, UUID de requêtes, affichage UTC/Ouagadougou, transport HTTP de la session/recherche/pagination, réponse paginée du catalogue, propagation des erreurs 503, état Riverpod sans configuration, parcours de création puis relecture d’un client. Les échanges HTTP du parcours sont contrôlés par un transport de test isolé : il ne s’agit pas d’un essai Flutter contre un serveur déployé.

Backend : authentification obligatoire, refus de modification catalogue pour employé, catalogue persisté en base de test, consentement explicite et retrait, révocation de session, rejet des signatures invalides, webhook signé et répété sans doublon, STOP excluant le client, calcul du montant serveur malgré un faux total client, création idempotente de commande, transitions interdites et historique, quantités et dépassement entier, normalisation téléphone, erreurs Meta explicites et ambiguës sans renvoi automatique. Les échecs Meta sont injectés par Mockito en test uniquement ; aucun accès à Meta n’est déclaré vérifié par ces tests.

## Blocages et travaux nécessaires

1. Activer/configurer les prérequis Windows des plugins, terminer l’installation de la plateforme Android 36 avec Android Studio ou l’outil Android officiel, puis relancer la compilation/recette Android avec identifiant et signature définitifs. Le NDK requis est désormais installé.
2. Rendre le moteur Docker Linux accessible, exécuter le test PostgreSQL réel et valider l’image et le volume média. Le test H2 ne remplace pas ces vérifications.
3. Fournir hors dépôt les secrets serveur, un domaine HTTPS et les accès/validations Meta nécessaires ; tester le webhook et un échange officiel sur numéro de test.
4. **Développer le parcours Embedded Signup manquant**, valider la coexistence auprès de Meta et terminer les adaptateurs d’historique après accès aux schémas officiels. Les événements correspondants sont seulement conservés en attente de revue.
5. Développer les médias WhatsApp et, si retenu, l’importeur d’archives externes. FCM reste absent.
6. Tester sauvegarde/restauration et le parcours complet sur un compte éligible avant toute action sur le numéro actuel.

Cette livraison n’est pas une validation de production de l’ensemble du cahier des charges. Elle ne migre pas le numéro et ne modifie pas les données du téléphone.

## Correction de l’affichage Chrome

Le lancement Web utilise désormais un indicateur de chargement, une erreur récupérable et le moteur CanvasKit servi localement (`--no-web-resources-cdn`). La page et les scripts CanvasKit standard/Chromium ont répondu HTTP 200 sur `http://localhost:8787`.

Le service officiel de diagnostic Flutter dans Chrome a confirmé `isWidgetTreeReady=true`, un arbre contenant `LoginPage` et `MaterialApp` sans `ErrorWidget`, ainsi que la première image envoyée et rasterisée. Cela vérifie le démarrage de l’écran de connexion, sans connecter de compte ni inventer de données.

Le test automatisé avec `--platform chrome` n’a pas pu aboutir : le snapshot SDK référence un fichier de test absent dans `C:/b/s/w/ir/x/w/archive/.pub-cache/.../host.dart.js`. Il ne doit pas être compté comme réussi. Le serveur Docker était à nouveau inaccessible pendant cette correction ; la connexion HTTP de bout en bout au backend n’a donc pas été validée dans cette session. L’origine navigateur locale est configurée dans `.env` ; elle prend effet au redémarrage du conteneur API.
