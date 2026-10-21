# Premier essai sur Render Free

Ce déploiement héberge le backend et une nouvelle base de test. Il ne transfère pas les données locales et n’active pas WhatsApp. Ne pas utiliser les vraies données du restaurant pour ce premier essai.

## 1. Préparer le dépôt

Mettre le projet dans un dépôt GitHub privé, avec les fichiers render.yaml et backend/. Render doit être autorisé à lire ce dépôt. Ne jamais envoyer .env, les clés Android, les sauvegardes ou les secrets. Utiliser Git ou GitHub Desktop pour que .gitignore soit respecté ; ne pas glisser tout le dossier dans le formulaire d’envoi de fichiers du site GitHub. Le dossier build/ est exclu par .gitignore.

## 2. Créer les services

1. Ouvrir https://dashboard.render.com et créer un compte.
2. Choisir New → Blueprint et connecter le dépôt privé.
3. Render détecte render.yaml. Vérifier que les deux ressources sont Free : restaurant-connect-bf-api et restaurant-connect-bf-db. Ne pas confirmer une offre payante pour ce test.
4. Saisir uniquement BOOTSTRAP_ADMIN_EMAIL et BOOTSTRAP_ADMIN_PASSWORD dans Render : votre adresse et un nouveau mot de passe fort de 14 à 72 caractères, au plus 72 octets UTF-8. Ne pas les envoyer dans le chat.
5. Lancer le déploiement et attendre que le service soit Live.

Le Blueprint relie la base sans copier son mot de passe, utilise PostgreSQL 17 et limite les connexions/mémoire pour un premier essai. La base n’est pas accessible publiquement. Render génère les deux secrets locaux du webhook et du chiffrement ; ce ne sont pas des accès Meta.

Le .env de votre ordinateur n’est pas lu par Render. Ne pas importer tout ce fichier : ses valeurs locales, notamment DATABASE_URL, ne correspondent pas au serveur distant.

## 3. Vérifier et connecter le téléphone

Copier l’URL HTTPS exacte affichée par Render. Dans le navigateur, ouvrir cette URL suivie de /actuator/health. Le résultat attendu est :

```json
{"status":"UP"}
```

L’URL seule peut afficher une erreur 401 : le serveur expose une API protégée, pas un site d’accueil. Cela ne signifie pas nécessairement que le déploiement a échoué.

Transmettre uniquement l’URL publique du service pour préparer l’application Android. Aucun jeton, mot de passe ou lien de base de données n’est nécessaire dans le chat. La compilation de test utilisera :

```powershell
flutter build apk --debug --dart-define=API_BASE_URL=https://VOTRE-SERVICE.onrender.com
```

Remplacer le domaine d’exemple par l’URL réelle, sans chemin /api et sans barre finale. L’application installée actuellement utilise localhost via USB : elle doit être recompilée pour Render. Une fois remplacée, elle utilisera Internet sans câble et sans le serveur de votre ordinateur.

Se connecter avec le compte saisi dans Render. Après vérification de l’accès, retirer BOOTSTRAP_ADMIN_EMAIL et BOOTSTRAP_ADMIN_PASSWORD de l’environnement Render et redéployer. Le compte persiste en base ; ces variables ne servent pas à réinitialiser son mot de passe.

## 4. Limites de cet essai

- Le service gratuit s’endort après 15 minutes sans requête et peut prendre environ une minute à se réveiller. L’application peut afficher une erreur réseau pendant le réveil : ouvrir /actuator/health, attendre UP, puis réessayer.
- Le disque web est éphémère. Les images téléversées peuvent disparaître à chaque redémarrage, déploiement ou mise en veille. Le stockage durable des images exige un service de stockage adapté ; il n’est pas fourni par ce Blueprint.
- PostgreSQL Free expire après 30 jours. Sauvegarder les données utiles avant expiration ; aucune sauvegarde automatique gratuite n’est présumée.
- Les limites de mémoire, compilation et réseau peuvent interrompre les essais. La configuration mémoire est préparée, mais aucun déploiement réel sur Render n’a encore été validé.
- La mise en veille rend cet hébergement inadapté à une validation de disponibilité continue des webhooks et des campagnes. Commencer par les clients, plats sans image et commandes, puis tester WhatsApp avec un compte de test autorisé. Ne pas contourner la veille par un service artificiel de requêtes répétées.

## 5. WhatsApp ensuite

L’URL publique Render sert automatiquement à ouvrir Embedded Signup. Dans Render, ajouter uniquement les variables Meta obtenues réellement : META_APP_ID, META_APP_SECRET, META_EMBEDDED_SIGNUP_CONFIG_ID, META_GRAPH_API_VERSION. Conserver les secrets générés WHATSAPP_TOKEN_ENCRYPTION_KEY et WHATSAPP_WEBHOOK_VERIFY_TOKEN, et sauvegarder la clé de chiffrement dans un coffre séparé.

Configurer chez Meta le domaine Render, /connect/whatsapp et /webhooks/whatsapp, vérifier le challenge et les champs officiels. Voir docs/META.md pour les conditions d’éligibilité et la recette. Ne pas enregistrer ni migrer le numéro actuel pour ce premier essai.

Références : https://render.com/docs/blueprint-spec et https://render.com/docs/free.

## Vérifications effectuées localement

- Les deux nouveaux fichiers YAML ont été parsés sans erreur.
- render.yaml a passé Test-Json contre le schéma officiel https://render.com/schema/render.yaml.json.
- docker build -t restaurant-render-check ./backend a terminé avec le code 0, en exécutant les tests prévus par le Dockerfile.
- Un PostgreSQL temporaire et une API temporaire ont démarré dans un réseau isolé : profils prod,render, port 10000 et limite mémoire Docker 512 Mo. /actuator/health a répondu UP ; consommation ponctuelle observée 261,7 Mio. Cela ne constitue pas un test de charge ni un déploiement Render réel.
- Les conteneurs, leur base temporaire, le réseau et le fichier de secrets de cet essai ont été supprimés. La base locale du restaurant est conservée.
