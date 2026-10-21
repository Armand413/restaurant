import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/api.dart';
import 'widgets.dart';

final whatsAppStatusProvider = FutureProvider.autoDispose<Json>(
  (ref) async =>
      Json.from(await ref.watch(apiProvider).call('/whatsapp/status') as Map),
);

class SettingsPanel extends ConsumerWidget {
  const SettingsPanel({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(authProvider).asData?.value;
    final admin = user?['role'] == 'ADMIN';
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          child: ListTile(
            leading: const Icon(Icons.storefront),
            title: const Text('Restaurant Connect BF'),
            subtitle: Text(
              '${user?['email'] ?? ''}\n${admin ? 'Administrateur' : 'Employé'} • FCFA\nAfrica/Ouagadougou',
            ),
            isThreeLine: true,
          ),
        ),
        if (admin) ...[
          Card(
            child: ListTile(
              leading: const Icon(Icons.link),
              title: const Text('Connecter WhatsApp Business'),
              subtitle: const Text(
                'Prérequis, état de connexion et historique',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => context.push('/whatsapp'),
            ),
          ),
          Card(
            child: ListTile(
              leading: const Icon(Icons.campaign_outlined),
              title: const Text('Menus et campagnes'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => context.push('/campaigns'),
            ),
          ),
          Card(
            child: ListTile(
              leading: const Icon(Icons.edit_outlined),
              title: const Text('Nom du restaurant'),
              onTap: () => editForm(
                context,
                title: 'Restaurant',
                fields: const [FieldSpec('name', 'Nom du restaurant')],
                save: (data) async {
                  await ref
                      .read(apiProvider)
                      .call('/settings', method: 'PUT', data: data);
                },
              ),
            ),
          ),
          Card(
            child: ListTile(
              leading: const Icon(Icons.person_add_outlined),
              title: const Text('Créer un accès employé'),
              onTap: () => editForm(
                context,
                title: 'Nouvel employé',
                fields: const [
                  FieldSpec('email', 'Adresse e-mail'),
                  FieldSpec('password', 'Mot de passe (14 à 72 caractères)'),
                ],
                save: (data) async {
                  await ref
                      .read(apiProvider)
                      .call(
                        '/auth/users',
                        method: 'POST',
                        data: {...data, 'role': 'EMPLOYEE'},
                      );
                },
              ),
            ),
          ),
        ],
        const SizedBox(height: 24),
        OutlinedButton.icon(
          onPressed: () async {
            try {
              await ref.read(authProvider.notifier).logout();
            } catch (e) {
              if (context.mounted) showError(context, e);
            }
          },
          icon: const Icon(Icons.logout),
          label: const Text('Se déconnecter et révoquer la session'),
        ),
      ],
    );
  }
}

class WhatsAppPage extends ConsumerStatefulWidget {
  const WhatsAppPage({super.key});
  @override
  ConsumerState<WhatsAppPage> createState() => _WhatsAppPageState();
}

class _WhatsAppPageState extends ConsumerState<WhatsAppPage> {
  Json? verification;
  bool busy = false;
  Future<void> connect() async {
    setState(() => busy = true);
    try {
      final session = Json.from(
        await ref.read(apiProvider).call('/whatsapp/onboarding', method: 'POST')
            as Map,
      );
      final opened = await launchUrl(
        Uri.parse(session['url'] as String),
        mode: LaunchMode.externalApplication,
      );
      if (!opened) throw StateError('Navigateur indisponible');
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> verify() async {
    setState(() => busy = true);
    try {
      final v = Json.from(
        await ref.read(apiProvider).call('/whatsapp/verify', method: 'POST')
            as Map,
      );
      if (mounted) setState(() => verification = v);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('WhatsApp Business'),
      actions: [
        IconButton(
          onPressed: () => ref.invalidate(whatsAppStatusProvider),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ref
        .watch(whatsAppStatusProvider)
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(errorText(e)),
                  TextButton(
                    onPressed: () => ref.invalidate(whatsAppStatusProvider),
                    child: const Text('Réessayer'),
                  ),
                ],
              ),
            ),
          ),
          data: (status) => ListView(
            padding: const EdgeInsets.all(20),
            children: [
              const Icon(
                Icons.verified_user_outlined,
                size: 48,
                color: Color(0xff13795b),
              ),
              const SizedBox(height: 16),
              if (status['phone'] != null)
                ListTile(
                  title: Text('Numéro associé : ${status['phone']}'),
                  subtitle: const Text(
                    'Coexistence confirmée par Meta lors de la connexion. Actualisez pour consulter les événements reçus.',
                  ),
                ),
              Text(
                'Connecter le numéro existant',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 12),
              const Text(
                'Effectuez une sauvegarde récente depuis WhatsApp Business avant toute connexion. Le numéro actuel doit rester utilisable sur le téléphone. La coexistence et l’accès à l’historique dépendent de la validation de Meta.',
              ),
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    status['configured'] == true
                        ? 'Variables serveur renseignées. La connexion reste à vérifier.'
                        : status['onboardingAvailable'] == true
                        ? 'Serveur prêt pour le parcours Meta. La connexion du numéro reste à autoriser et à vérifier.'
                        : 'Connexion WhatsApp non activée. La préparation du serveur et de l’application Meta doit être terminée avant de connecter votre numéro.',
                  ),
                ),
              ),
              if (status['onboardingAvailable'] == true)
                FilledButton.icon(
                  onPressed: busy ? null : connect,
                  icon: const Icon(Icons.link),
                  label: const Text('Continuer avec Meta'),
                ),
              if ((status['missingConfiguration'] as List? ?? [])
                  .isNotEmpty) ...[
                const Text(
                  'Éléments manquants côté serveur',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                for (final missing in status['missingConfiguration'] as List)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Text('• $missing'),
                  ),
              ],
              if (status['configured'] == true)
                FilledButton.tonal(
                  onPressed: busy ? null : verify,
                  child: Text(
                    busy
                        ? 'Vérification auprès de Meta…'
                        : 'Vérifier l’accès API auprès de Meta',
                  ),
                ),
              if (verification != null)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      'Accès API confirmé lors de la vérification\nNuméro : ${verification!['phone']['display_phone_number']}\nCompte : ${verification!['phone']['verified_name']}\nCoexistence : ${verification!['coexistence'] == 'CONFIRMED' ? 'confirmée par Meta' : 'en attente de confirmation'}\nRéception des webhooks : à vérifier',
                    ),
                  ),
                ),
              for (final check in status['lastApiCheck'] as List? ?? [])
                ListTile(
                  title: Text(
                    'Dernier numéro vérifié : ${check['display_phone']}',
                  ),
                  subtitle: Text(
                    '${check['verified_name']} • ${dateText(check['checked_at'])}',
                  ),
                ),
              for (final event in status['lastWebhook'] as List? ?? [])
                ListTile(
                  title: const Text('Dernier webhook traité'),
                  subtitle: Text(dateText(event['received_at'])),
                ),
              const SizedBox(height: 16),
              Text(
                'Parcours officiel Meta',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Text('${status['reason']}'),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () async {
                  try {
                    final opened = await launchUrl(
                      Uri.parse(
                        'https://developers.facebook.com/documentation/business-messaging/whatsapp/embedded-signup/onboarding-business-app-users',
                      ),
                      mode: LaunchMode.externalApplication,
                    );
                    if (!opened && context.mounted) {
                      showError(context, StateError('Navigateur indisponible'));
                    }
                  } catch (e) {
                    if (context.mounted) showError(context, e);
                  }
                },
                icon: const Icon(Icons.open_in_new),
                label: const Text('Consulter les prérequis officiels'),
              ),
              const SizedBox(height: 24),
              Text(
                'Historique WhatsApp',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const Text('Synchronisation officielle Meta'),
              for (final entry in {
                'Dernière synchronisation': status['lastSyncAt'] == null
                    ? null
                    : dateText(status['lastSyncAt']),
                'Contacts importés': status['contactsImported'],
                'Conversations importées': status['conversationsImported'],
                'Messages importés': status['messagesImported'],
                'Progression fournie par Meta': status['progress'] == null
                    ? null
                    : '${status['progress']} %',
              }.entries)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(entry.key),
                  subtitle: Text(
                    entry.value?.toString() ?? 'En attente de confirmation',
                  ),
                ),
              if ((status['pendingEvents'] as num) > 0)
                Text(
                  '${status['pendingEvents']} événements authentifiés en attente de traitement.',
                ),
              for (final error in status['syncErrors'] as List? ?? [])
                ListTile(
                  leading: const Icon(Icons.error_outline),
                  title: Text('Import ${error['field']} non terminé'),
                  subtitle: Text(
                    '${error['error_code']} • ${dateText(error['received_at'])}',
                  ),
                ),
              for (final error in status['onboardingErrors'] as List? ?? [])
                ListTile(
                  leading: const Icon(Icons.error_outline),
                  title: const Text('Connexion à vérifier'),
                  subtitle: Text(
                    '${error['state']} • ${error['error_code'] ?? 'Confirmation Meta manquante'}. Ne relancez pas une migration.',
                  ),
                ),
              for (final event in status['lastSync'] as List)
                Card(
                  child: ListTile(
                    title: Text('${event['field']}'),
                    subtitle: Text(
                      '${event['details']}\n${dateText(event['received_at'])}',
                    ),
                  ),
                ),
              const SizedBox(height: 16),
              Text(
                'Archives externes',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const Text(
                'L’importation d’archives exportées n’est pas disponible dans cette version. Une archive ne restaure pas les messages dans WhatsApp. L’API ne lit pas les sauvegardes Google Drive ou iCloud.',
              ),
            ],
          ),
        ),
  );
}

class CampaignsPage extends ConsumerStatefulWidget {
  const CampaignsPage({super.key});
  @override
  ConsumerState<CampaignsPage> createState() => _CampaignsPageState();
}

class _CampaignsPageState extends ConsumerState<CampaignsPage> {
  final listKey = GlobalKey<RemoteListState>();
  bool busy = false;
  Future<void> create() async {
    final menu = await pickRecord(
      context,
      '/menus',
      'Choisir le menu à diffuser',
    );
    if (menu == null || !mounted) return;
    final selected = <Json>[];
    while (mounted) {
      final c = await pickRecord(
        context,
        '/customers',
        selected.isEmpty
            ? 'Choisir un destinataire'
            : 'Ajouter un client, ou annuler pour terminer',
      );
      if (c == null) break;
      if (!selected.any((e) => e['id'] == c['id'])) selected.add(c);
    }
    if (!mounted || selected.isEmpty) return;
    final result = <String, dynamic>{};
    final done = await editForm(
      context,
      title: 'Préparer la campagne',
      initial: {'language': 'fr'},
      fields: const [
        FieldSpec('templateName', 'Nom du modèle marketing approuvé'),
        FieldSpec('language', 'Code langue'),
      ],
      save: (data) async {
        result.addAll(
          Json.from(
            await ref
                    .read(apiProvider)
                    .call(
                      '/campaigns',
                      method: 'POST',
                      data: {
                        ...data,
                        'menuId': menu['id'],
                        'customerIds': selected.map((c) => c['id']).toList(),
                      },
                    )
                as Map,
          ),
        );
      },
    );
    if (!done || !mounted) return;
    listKey.currentState?.load(reset: true);
    await launch(
      result['id'] as String,
      '${result['preview']}',
      result['recipientCount'] as int,
    );
  }

  Future<void> launch(String id, String preview, int count) async {
    if (!await confirm(
      context,
      'Envoyer à $count destinataires autorisés ?',
      '$preview\n\nLe consentement sera vérifié de nouveau avant chaque envoi. Cette action lance de vrais messages WhatsApp.',
    )) {
      return;
    }
    if (!mounted) return;
    setState(() => busy = true);
    try {
      await ref
          .read(apiProvider)
          .call(
            '/campaigns/$id/confirm',
            method: 'POST',
            data: {'confirmed': true},
          );
      listKey.currentState?.load(reset: true);
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> detail(Json item) async {
    try {
      final data = Json.from(
        await ref.read(apiProvider).call('/campaigns/${item['id']}') as Map,
      );
      if (!mounted) return;
      final recipients = data['recipients'] as List;
      final campaign = data['campaign'] as Map;
      final send = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(label(campaign['status'])),
          content: SizedBox(
            width: 440,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${campaign['preview']}'),
                  const Divider(),
                  Text('${recipients.length} destinataires enregistrés'),
                  for (final r in recipients)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text('${r['name']}'),
                      subtitle: Text(
                        '${label(r['delivery_status'] ?? r['status'])}${r['error_code'] == null ? '' : ' • ${r['error_code']}'}',
                      ),
                    ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Fermer'),
            ),
            if (campaign['status'] == 'DRAFT')
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Prévisualiser et confirmer'),
              ),
          ],
        ),
      );
      if (send == true && mounted) {
        await launch(
          item['id'] as String,
          '${campaign['preview']}',
          recipients.length,
        );
      }
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> menus() async {
    final menu = await pickRecord(context, '/menus', 'Historique des menus');
    if (menu == null || !mounted) return;
    try {
      final data = Json.from(
        await ref.read(apiProvider).call('/menus/${menu['id']}') as Map,
      );
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('${menu['title']}'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final item in data['items'] as List)
                  ListTile(
                    title: Text('${item['name']}'),
                    trailing: Text('${item['price']} FCFA'),
                  ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Fermer'),
            ),
          ],
        ),
      );
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Menus et campagnes'),
      actions: [
        IconButton(
          onPressed: menus,
          icon: const Icon(Icons.history),
          tooltip: 'Historique des menus',
        ),
        IconButton(
          onPressed: () => listKey.currentState?.load(reset: true),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: Column(
      children: [
        const Padding(
          padding: EdgeInsets.all(20),
          child: Text(
            'Un brouillon ne déclenche aucun envoi. Un modèle marketing approuvé et un consentement explicite sont requis.',
          ),
        ),
        if (busy) const LinearProgressIndicator(),
        Expanded(
          child: RemoteList(
            key: listKey,
            path: '/campaigns',
            tile: (item) => Card(
              child: ListTile(
                title: Text('${item['template_name']}'),
                subtitle: Text(
                  '${label(item['status'])} • ${dateText(item['created_at'])}',
                ),
                onTap: () => detail(item),
              ),
            ),
          ),
        ),
      ],
    ),
    floatingActionButton: FloatingActionButton.extended(
      onPressed: busy
          ? null
          : () async {
              try {
                await create();
              } catch (e) {
                if (context.mounted) showError(context, e);
              }
            },
      icon: const Icon(Icons.add),
      label: const Text('Préparer'),
    ),
  );
}
