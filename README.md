# Restaurant Connect BF

Application Flutter en français et API Java 21/Spring Boot 3.5.16, PostgreSQL, Flyway.

## État réel de cette livraison

Ce dépôt contient une implémentation connectée, sans clients, messages ou commandes de démonstration en production. **Il ne satisfait pas encore tous les critères d’une mise en production.** Embedded Signup et les imports de coexistence sont codés et testés en isolation ; leur configuration et leur validation avec un compte Meta éligible restent obligatoires. Aucun numéro existant n’a été enregistré, migré ou déconnecté.

| Domaine | Implémentation | Limites actuelles |
| --- | --- | --- |
| Authentification | BCrypt, sessions opaques hachées en base, expiration 12 h, déconnexion avec révocation, administrateur/employé, limitation de débit | Pas de récupération de mot de passe ni de MFA ; provisionnement initial par environnement |
| Clients | Création, modification, recherche, pagination, catégories et étiquettes, registre de consentement et désabonnement | Téléphone normalisé et contrôlé syntaxiquement au format E.164 ; propriété du numéro non vérifiée |
| Plats | CRUD, disponibilité, prix entier FCFA, recherche, image JPEG/PNG conservée sur volume | Plats référencés protégés contre la suppression ; téléversement limité à 5 Mo |
| Menus | Composition, instantané des noms/prix, historique | Pas d’édition d’un instantané déjà créé |
| Commandes | Plusieurs articles, prix serveur figé, total entier, retrait/livraison, transitions contrôlées, historique, clé de création idempotente | Pas d’encaissement ; aucune création ne marque une commande payée |
| Discussions | Liste, filtres statut/catégorie, recherche, non-lus locaux, historique paginé, réponse texte, statuts Meta | Mise à jour par interrogation automatique (5 s en conversation, 15 s en liste), arrêt en arrière-plan ; pas de notification push |
| Cloud API | Client HTTP officiel, délais, vérification webhook, signature HMAC SHA-256, vérification WABA/numéro, messages entrants et statuts idempotents, STOP | Aucun envoi réel testé faute de configuration Meta ; l’envoi et l’affichage de médias WhatsApp restent à développer |
| Campagnes | Brouillon persistant, consentements, modèle MARKETING approuvé contrôlé auprès de Meta, aperçu, confirmation distincte, file PostgreSQL, un destinataire toutes les 2 s | Modèle limité à un paramètre texte de corps `{{1}}`, sans bouton ni en-tête dynamique ; pas de reprise automatique d’un envoi ambigu |
| Coexistence/historique | Parcours officiel dans le navigateur, échange côté serveur, jeton chiffré, contrôle de coexistence, confirmation, souscription et demandes uniques ; import contacts, historique et échos avec progression Meta et déduplication | **Non activé et non testé avec un compte Meta réel.** Application Meta autorisée et HTTPS public nécessaires. Modifications/révocations à compléter ; médias non affichés. |
| Archives externes | Limites expliquées dans l’application | Importeur d’archives texte non implémenté ; aucune lecture Google Drive/iCloud |
| Serveur | Docker, volumes, reverse proxy Caddy HTTPS, santé, profils local/test/prod, logs structurés | Déploiement et certificat à valider sur un domaine réel ; une seule instance API/worker supportée |

Le compteur « non lu » est interne à l’équipe. Ouvrir une conversation ne transmet pas un accusé de lecture à WhatsApp. Les états SENT/DELIVERED/READ viennent uniquement des événements signés ; ACCEPTED signifie seulement que Meta a retourné un identifiant. UNKNOWN signifie que le résultat d’envoi n’est pas établi : contrôler la discussion et les événements, ne pas renvoyer aveuglément.

## Organisation

- `lib/core/` : API Dio, stockage sécurisé du seul jeton de session, authentification Riverpod.
- `lib/features/` : discussions, commandes, catalogue, clients, paramètres et campagnes.
- `lib/app.dart` : Material 3, navigation GoRouter et contrôle d’accès visuel.
- `backend/` : API Spring Security, JPA pour le catalogue et SQL transactionnel pour les opérations métier.
- `backend/src/main/resources/db/migration/` : schéma versionné, contraintes, index ; aucune donnée métier fictive.
- `backend/src/test/` et `test/` : données et doubles de test isolés.
- `deploy/` : HTTPS et procédures d’exploitation.
- `docs/META.md` : configuration Meta et préservation du numéro.
- `docs/VALIDATION.md` : résultats réellement constatés.

Les données de test utilisent des noms explicitement isolés et ne sont pas chargées par le profil de production. La devise est XOF (FCFA, sans décimale), les heures sont affichées en UTC, identique à Africa/Ouagadougou.

## Prérequis

Flutter stable compatible avec Dart 3.13.5, Java 21, Maven 3.9+, Docker Compose et PostgreSQL 17.11. Le verrou Flutter `pubspec.lock` est conservé. Sur Windows, activer le mode développeur pour les liens symboliques des plugins si `flutter pub get` le demande.

Le lanceur officiel Maven 3.9.16 est inclus dans `backend/` : sous Windows utiliser `./mvnw.cmd` à la place de `mvn`, sous Linux `sh mvnw`. Il télécharge Maven depuis le dépôt officiel au premier lancement. Aucun changement global du PATH n’est nécessaire.

Versions vérifiées : [Spring Boot 3.5.16](https://docs.spring.io/spring-boot/3.5/), [PostgreSQL 17.11](https://www.postgresql.org/docs/17/release-17-11.html). Réexaminer régulièrement les versions correctives et vulnérabilités avant déploiement ; figer les images par digest dans la chaîne de livraison après validation.

## Configuration et lancement local

Pour un premier hébergement de test gratuit, utiliser le Blueprint [render.yaml](render.yaml) et suivre [la procédure Render](deploy/RENDER.md). Aucun déploiement externe n’est déclenché par ces fichiers. Le service gratuit et son stockage présentent les limites précisées dans cette procédure.

1. Copier `.env.example` vers `.env`, qui est ignoré par Git.
2. Renseigner un mot de passe PostgreSQL unique et un administrateur initial : `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` (14 à 72 caractères, au plus 72 octets UTF-8). Ne pas transmettre ces valeurs dans le chat.
3. Pour un serveur complet derrière HTTPS :

```sh
docker compose up -d --build
docker compose ps
```

Le domaine `PUBLIC_HOST` doit pointer sur le serveur et les ports 80/443 doivent être accessibles pour le certificat. `.env.example` utilise un domaine réservé et ne constitue pas une configuration exploitable telle quelle.

Pour développer avec PostgreSQL local seulement :

```sh
docker compose -f compose.yaml -f compose.local.yaml up -d db
cd backend
# Charger POSTGRES_PASSWORD, POSTGRES_USER et les deux variables BOOTSTRAP dans l’environnement du processus.
# DATABASE_URL=jdbc:postgresql://localhost:5432/restaurant
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Le fichier `.env` est lu par Docker Compose, pas automatiquement par Maven. Le premier démarrage crée l’administrateur uniquement si aucun utilisateur n’existe ; retirer ensuite les deux variables BOOTSTRAP et recréer le conteneur API. Aucun mot de passe par défaut n’existe. `BOOTSTRAP_ADMIN_PASSWORD` ne réinitialise pas un compte existant.

Application sur émulateur Android en développement :

```sh
flutter pub get
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Sur téléphone physique, employer une URL HTTPS accessible au téléphone. L’URL de l’API ne doit contenir ni chemin `/api` final, ni jeton. Le HTTP est permis uniquement dans la variante Android debug. En release, l’écran de connexion refuse une URL HTTP ou manquante.

Pour Chrome en développement local, régler `CORS_ORIGIN=http://localhost:8787` dans `.env`, puis utiliser un port fixe. Le moteur graphique est servi localement pour éviter un écran blanc lorsque le CDN est inaccessible :

```sh
docker compose up -d --no-deps api
flutter run -d chrome --web-port=8787 --no-web-resources-cdn --dart-define=API_BASE_URL=http://localhost:8080
```

Ouvrir `http://localhost:8787`. La page affiche une étape de chargement et une erreur récupérable si le démarrage Flutter échoue.

## Vérifications et compilation

```sh
flutter pub get
dart format lib test
flutter analyze
flutter test
cd backend
mvn test
mvn verify
```

Le test PostgreSQL Testcontainers exige un moteur Docker Linux actif et l’accès au téléchargement de l’image. Il est explicitement ignoré en son absence ; un test ignoré ne constitue pas une validation PostgreSQL. Les tests API H2 exécutent les migrations Flyway et les parcours métier sur une base de test, sans contacter Meta.

Android : copier `android/key.properties.example` en `android/key.properties` et renseigner localement le chemin du keystore et les mots de passe. Sauvegarder le keystore en dehors du dépôt.

```sh
flutter build apk --release --dart-define=API_BASE_URL=https://votre-domaine
flutter build appbundle --release --dart-define=API_BASE_URL=https://votre-domaine
```

Aucune clé de signature réelle n’est fournie. La signature debug a été retirée de la variante release. Sans `key.properties`, un éventuel APK non signé n’est pas distribuable. Choisir et figer l’identifiant Android avant publication : le projet conserve actuellement `com.example.restaurant` pour ne pas changer silencieusement l’identité du projet initial. Remplacer cet identifiant et le namespace de façon cohérente pour votre publication.

Sur cette machine, la compilation a rencontré une interruption réseau pendant l’installation de la plateforme Android 36, nécessaire aux plugins. Installer « Android SDK Platform 36 » depuis le gestionnaire SDK Android Studio avant de relancer. Le NDK 28.2.13676358 a été installé avec succès pendant la validation. Aucun APK final n’a été produit.

iOS est conservé ; compiler et configurer la signature sur macOS. FCM n’est pas configuré : aucune notification push n’est annoncée opérationnelle.

## API

Toutes les routes `/api/` exigent `Authorization: Bearer <session>`, sauf `POST /api/auth/login`. Les secrets Meta restent exclusivement côté serveur. `/actuator/health` est public et n’expose aucun détail. `/webhooks/whatsapp` est public pour Meta, mais son POST exige une signature valide.

- Auth : `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout`, création de compte par administrateur `POST /api/auth/users`.
- Catalogue : `GET/POST /api/dishes`, `PUT/DELETE /api/dishes/{id}`, `POST /api/media`, `GET /api/media/{nom}`.
- Clients : `GET/POST /api/customers`, `GET/PUT /api/customers/{id}`, `POST /api/customers/{id}/consent`.
- Menus : `GET/POST /api/menus`, `GET /api/menus/{id}`.
- Commandes : `GET/POST /api/orders`, `GET /api/orders/{id}`, `POST /api/orders/{id}/status`.
- Discussions : `GET/POST /api/conversations`, `PUT /api/conversations/{id}`, `GET/POST /api/conversations/{id}/messages`, `POST /api/conversations/{id}/read`.
- Campagnes administrateur : `GET/POST /api/campaigns`, `GET /api/campaigns/{id}`, `POST /api/campaigns/{id}/confirm` avec `{"confirmed":true}`.
- WhatsApp administrateur : `GET /api/whatsapp/status`, vérification de l’accès API en lecture `POST /api/whatsapp/verify`.

Les listes acceptent `page` à partir de 0, généralement 30 résultats (50 pour les messages). Recherche `q`, filtres `status` et `category` sur les discussions. Les messages sont renvoyés du plus récent au plus ancien. Les modifications de plat exigent sa `version` courante pour éviter l’écrasement concurrent. Les clés UUID `requestKey` de commande et de message doivent être conservées lors d’une reprise ; la réutilisation avec un autre contenu est refusée.

OpenAPI : profil `local`, `/swagger-ui/index.html`, `/v3/api-docs`, accès authentifié. En production, documentation désactivée par défaut ; ne pas exposer l’interface publiquement.

## Déploiement et prochaines étapes

Lire [l’exploitation HTTPS et les sauvegardes](deploy/EXPLOITATION.md), [la configuration Meta](docs/META.md) et [la validation](docs/VALIDATION.md). Ne pas publier l’application comme entièrement opérationnelle avant résolution des fonctions manquantes, validation PostgreSQL, essai Android et tests réels Meta sur un environnement dédié.
