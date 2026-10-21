# Connexion officielle Meta et préservation du numéro

## État réel

Embedded Signup est codé : lien administrateur de dix minutes, navigateur externe et SDK officiel Meta, échange du code côté serveur, contrôle du jeton, de son application et des autorisations, identification du numéro et vérification is_on_biz_app=true / platform_type=CLOUD_API. Après confirmation du numéro : souscription du compte et demandes uniques de synchronisation. Le jeton est chiffré avec AES-256-GCM en base ; la clé reste sur le serveur.

Les événements signés history, smb_app_state_sync et smb_message_echoes sont persistés puis traités par une file PostgreSQL. Les messages conservent leurs identifiants et leur provenance. La progression vient des métadonnées Meta. Les anciens messages ne créent ni fenêtre de service API, ni consentement marketing. Supprimer un contact WhatsApp ne détruit pas ses commandes ou messages locaux.

**Non activé et non testé avec un compte Meta réel. L’application n’est pas prête à commercialiser.** Les réponses Meta isolées des tests ne démontrent ni éligibilité ni accord Meta ni récupération d’un véritable historique.

## Références officielles

Les pages officielles ont été accessibles en HTTP direct le 21 octobre 2026 après les erreurs 429 du moteur de recherche :

- [Implémentation Embedded Signup](https://developers.facebook.com/documentation/business-messaging/whatsapp/embedded-signup/implementation/).
- [Coexistence, événements et synchronisation](https://developers.facebook.com/documentation/business-messaging/whatsapp/embedded-signup/onboarding-business-app-users/).
- [Collection officielle Meta](https://www.postman.com/meta/whatsapp-business-platform/documentation/du6gzjv/embedded-signup).

Utiliser une configuration Facebook Login for Business actuelle avec coexistence ; le retrait des versions Embedded Signup 2 et 3 est annoncé au 15 octobre 2026. La version du parcours est configurée dans Meta, distinctement de la version Graph API.

## Préparation extérieure obligatoire

1. Application Meta avec produit WhatsApp et statut requis pour la coexistence. Le guide indique un partenaire de solution ou fournisseur de technologies. Vérifier les exigences du compte et du pays ; aucune éligibilité n’est supposée.
2. Facebook Login for Business et Embedded Signup avec whatsapp_business_management et whatsapp_business_messaging. Terminer vérification d’entreprise, contrôles d’accès et App Review requis pour les entreprises visées. Le mode développement ne vaut pas autorisation commerciale.
3. API sur un domaine HTTPS public. Autoriser exactement le domaine et https://votre-domaine/connect/whatsapp dans les réglages Meta applicables : domaines, SDK JavaScript et retours OAuth. Prévoir les politiques de confidentialité et suppression des données requises.
4. Webhook https://votre-domaine/webhooks/whatsapp, verify token et champs messages, history, smb_app_state_sync, smb_message_echoes, account_update. Le challenge doit aboutir avant un nouveau parcours. Il ne prouve pas la réception de vrais événements ou la souscription de chaque champ.
5. Essais avec les actifs officiels de test, puis avec un compte WhatsApp Business de test réellement éligible pour la coexistence et l’historique. Un numéro de test Cloud API ne garantit pas cette éligibilité.

## Variables serveur

Ne pas transmettre les secrets dans le chat et ne pas les committer. Voir .env.example.

| Variable | Origine et usage |
| --- | --- |
| PUBLIC_API_URL | Origine HTTPS publique de l’API, sans chemin |
| META_APP_ID | Identifiant de l’application Meta |
| META_EMBEDDED_SIGNUP_CONFIG_ID | Configuration Facebook Login for Business avec coexistence |
| META_APP_SECRET | Secret de l’application, uniquement serveur |
| META_GRAPH_API_VERSION | Version Graph supportée, format vNN.0 |
| WHATSAPP_WEBHOOK_VERIFY_TOKEN | Secret local du challenge |
| WHATSAPP_TOKEN_ENCRYPTION_KEY | 32 octets aléatoires en Base64 ; sauvegarde séparée de PostgreSQL |
| WHATSAPP_DEFAULT_LANGUAGE | fr par défaut ; chaque modèle vérifie sa langue réelle |
| WHATSAPP_PHONE_NUMBER_ID / WHATSAPP_BUSINESS_ACCOUNT_ID / WHATSAPP_ACCESS_TOKEN | Mode manuel ou numéro de test ; les actifs Embedded Signup stockés en base prennent priorité |

Générer la clé localement dans PowerShell, puis la conserver sur le serveur et dans le coffre de sauvegarde :

```powershell
[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Ne pas changer cette clé après connexion sans rechiffrer les données. Une restauration de PostgreSQL sans la clé ne déchiffre pas le jeton. Aucun identifiant ni accord Meta n’est inventé.

## Parcours administrateur

1. Sauvegarde récente depuis WhatsApp Business. Ne supprimer ni application, ni compte, ni conversations, ni sauvegarde.
2. Paramètres → Connecter WhatsApp Business. Les éléments manquants sont affichés ; Continuer avec Meta apparaît quand les prérequis serveur sont présents et le challenge vérifié.
3. Lire les conséquences et confirmer l’ouverture du parcours. Saisir identifiants, vérifications et PIN uniquement chez Meta. Vérifier numéro et mode avant autorisation. Si Meta demande migration, suppression ou déconnexion : interrompre.
4. Le serveur exige FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING et vérifie le numéro auprès de Graph. Aucun appel d’enregistrement, désenregistrement ou suppression de numéro n’est effectué.
5. Vérifier nom, compte et numéro retournés, puis confirmer association et import. Aucun remplacement automatique d’un numéro associé.
6. Le serveur souscrit au compte puis demande smb_app_state_sync et history une seule fois chacun. Il conserve le request_id reçu et ne répète pas un appel ambigu. Garder WhatsApp Business ouvert et actualiser la page dans l’application.

Le guide impose un délai de 24 heures pour l’intégration/synchronisation. Ne pas lancer un vrai onboarding avant que serveur, webhook et traitements soient prêts. Une requête d’import acceptée ne signifie pas que l’historique est partagé ou reçu.

## Limites et incidents

- Conversations individuelles et période limitée par Meta ; groupes et sauvegardes Google Drive/iCloud exclus. Identifiants de médias conservés ; téléchargement et affichage WhatsApp restent à développer.
- Modifications/révocations non appliquées au message d’origine : gestion complète encore bloquante pour le lancement commercial.
- Segments et pourcentages viennent de Meta. L’absence d’événement ne démontre pas une récupération exhaustive. Code 2593109 : partage désactivé/refusé.
- Échange du code interrompu : nouveau lien, sans rééchange. Activation/import interrompu : vérification opérateur auprès de Meta, sans nouvelle demande aveugle.
- Après diagnostic/correction, un administrateur peut demander POST /api/whatsapp/events/{event_key}/retry : retraitement local uniquement, aucun envoi ou appel de synchronisation Meta. Aucun payload privé n’est renvoyé par cet endpoint.
- Import d’archives texte externes non implémenté ; il ne restaurerait pas les messages dans WhatsApp.

## Recette avant commercialisation

Compte éligible : autorisation réelle, numéro, maintien de WhatsApp Business sur téléphone, webhook, import, progression, doublons, échos, statuts, refus d’historique, coupures et erreurs. Tester les envois vers un destinataire autorisé et les campagnes avec confirmation et consentement. Vérifier révocation d’accès, sauvegardes/restaurations et renouvellement des accès. Conserver les preuves sans jetons dans les logs.
