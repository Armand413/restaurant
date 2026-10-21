import 'dart:async';

import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/api.dart';
import 'widgets.dart';
import 'settings.dart';
import 'orders.dart';

class WorkspacePage extends ConsumerStatefulWidget {
  const WorkspacePage({super.key, required this.section});
  final String section;
  @override
  ConsumerState<WorkspacePage> createState() => _WorkspacePageState();
}

class _WorkspacePageState extends ConsumerState<WorkspacePage>
    with WidgetsBindingObserver {
  static const paths = [
    'discussions',
    'menu',
    'commandes',
    'clients',
    'parametres',
  ];
  static const titles = [
    'Discussions',
    'Menu',
    'Commandes',
    'Clients',
    'Paramètres',
  ];
  final listKey = GlobalKey<RemoteListState>();
  String q = '', status = '', category = '';
  Timer? timer;
  bool foreground = true;
  bool get admin => ref.read(authProvider).asData?.value?['role'] == 'ADMIN';
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    timer = Timer.periodic(const Duration(seconds: 15), (_) {
      if (foreground && widget.section == 'discussions') {
        listKey.currentState?.load(reset: true);
      }
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> dish([Json? item]) async {
    if (!admin) return;
    final changed = await editForm(
      context,
      title: item == null ? 'Ajouter un plat' : 'Modifier le plat',
      fields: const [
        FieldSpec('name', 'Nom'),
        FieldSpec('price', 'Prix en FCFA', number: true),
        FieldSpec('category', 'Catégorie', required: false),
        FieldSpec('description', 'Description', required: false, lines: 3),
      ],
      initial: item ?? {},
      save: (data) async {
        await ref
            .read(apiProvider)
            .call(
              '/dishes${item == null ? '' : '/${item['id']}'}',
              method: item == null ? 'POST' : 'PUT',
              data: {
                ...data,
                'available': item?['available'] ?? true,
                'version': item?['version'] ?? 0,
                'imageUrl': item?['imageUrl'],
              },
            );
      },
    );
    if (changed) listKey.currentState?.load(reset: true);
  }

  Future<void> customer([Json? item]) async {
    final changed = await editForm(
      context,
      title: item == null ? 'Ajouter un client' : 'Modifier le client',
      fields: const [
        FieldSpec('name', 'Nom'),
        FieldSpec('phone', 'Téléphone international (+226…)'),
        FieldSpec('category', 'Catégorie', required: false),
        FieldSpec('tags', 'Étiquettes', required: false),
      ],
      initial: item ?? {},
      save: (data) async {
        await ref
            .read(apiProvider)
            .call(
              '/customers${item == null ? '' : '/${item['id']}'}',
              method: item == null ? 'POST' : 'PUT',
              data: data,
            );
      },
    );
    if (changed) listKey.currentState?.load(reset: true);
  }

  Future<void> openCustomer(Json item) async {
    try {
      final details = Json.from(
        await ref.read(apiProvider).call('/customers/${item['id']}') as Map,
      );
      if (!mounted) return;
      final action = await showModalBottomSheet<String>(
        context: context,
        isScrollControlled: true,
        builder: (context) => SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  '${item['name']}',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                Text('${item['phone']}'),
                Text(
                  item['unsubscribed'] == true
                      ? 'Désabonné'
                      : 'Consentement à vérifier dans le registre',
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, 'edit'),
                  child: const Text('Modifier'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, 'chat'),
                  child: const Text('Ouvrir la discussion'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, 'grant'),
                  child: const Text('Enregistrer un consentement explicite'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, 'revoke'),
                  child: const Text('Désabonner'),
                ),
                const Divider(),
                const Text('Registre des consentements'),
                for (final c in details['consents'] as List)
                  ListTile(
                    title: Text(
                      c['granted'] == true
                          ? 'Accord marketing'
                          : 'Retrait marketing',
                    ),
                    subtitle: Text(
                      '${c['source']} • ${dateText(c['recorded_at'])}',
                    ),
                  ),
                const Text('Commandes récentes'),
                for (final order in details['orders'] as List)
                  ListTile(
                    title: Text(
                      '${order['total']} FCFA • ${label(order['status'])}',
                    ),
                    subtitle: Text(dateText(order['created_at'])),
                  ),
              ],
            ),
          ),
        ),
      );
      if (!mounted) return;
      if (action == 'edit') {
        await customer(item);
      }
      if (action == 'chat') {
        final conversation = await ref
            .read(apiProvider)
            .call(
              '/conversations',
              method: 'POST',
              data: {'customerId': item['id']},
            );
        if (mounted) context.push('/discussions/${conversation['id']}');
      }
      if (action == 'grant' || action == 'revoke') {
        if (!mounted) return;
        await editForm(
          context,
          title: action == 'grant'
              ? 'Preuve du consentement marketing'
              : 'Motif du désabonnement',
          fields: const [
            FieldSpec('source', 'Source et référence de la demande'),
          ],
          save: (data) async {
            await ref
                .read(apiProvider)
                .call(
                  '/customers/${item['id']}/consent',
                  method: 'POST',
                  data: {
                    'source': data['source'],
                    'granted': action == 'grant',
                  },
                );
          },
        );
        listKey.currentState?.load(reset: true);
      }
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> dishAction(Json item, String action) async {
    try {
      if (action == 'image') {
        final image = await ImagePicker().pickImage(
          source: ImageSource.gallery,
          maxWidth: 1600,
          imageQuality: 85,
        );
        if (image == null) return;
        final result = await ref
            .read(apiProvider)
            .call(
              '/media',
              method: 'POST',
              data: FormData.fromMap({
                'file': MultipartFile.fromBytes(
                  await image.readAsBytes(),
                  filename: image.name,
                ),
              }),
            );
        await ref
            .read(apiProvider)
            .call(
              '/dishes/${item['id']}',
              method: 'PUT',
              data: {...item, 'imageUrl': result['url']},
            );
        listKey.currentState?.load(reset: true);
        return;
      }
      if (action == 'edit') {
        await dish(item);
        return;
      }
      if (action == 'delete') {
        if (!await confirm(
          context,
          'Supprimer ce plat ?',
          'Un plat utilisé par un menu ou une commande ne peut pas être supprimé.',
        )) {
          return;
        }
        await ref
            .read(apiProvider)
            .call('/dishes/${item['id']}', method: 'DELETE');
      } else {
        await ref
            .read(apiProvider)
            .call(
              '/dishes/${item['id']}',
              method: 'PUT',
              data: {...item, 'available': item['available'] != true},
            );
      }
      listKey.currentState?.load(reset: true);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> menu() async {
    final selected = <Json>[];
    while (mounted) {
      final d = await pickRecord(
        context,
        '/dishes',
        selected.isEmpty
            ? 'Premier plat du menu'
            : 'Ajouter un plat, ou annuler pour terminer',
      );
      if (d == null) break;
      if (d['available'] == true && !selected.any((e) => e['id'] == d['id'])) {
        selected.add(d);
      }
    }
    if (!mounted || selected.isEmpty) return;
    await editForm(
      context,
      title: 'Menu du jour (${selected.length} plats)',
      initial: {
        'date': DateTime.now().toUtc().toIso8601String().substring(0, 10),
      },
      fields: const [
        FieldSpec('title', 'Titre du menu'),
        FieldSpec('date', 'Date AAAA-MM-JJ'),
      ],
      save: (data) async {
        await ref
            .read(apiProvider)
            .call(
              '/menus',
              method: 'POST',
              data: {...data, 'dishIds': selected.map((e) => e['id']).toList()},
            );
      },
    );
  }

  Future<void> add() async {
    switch (widget.section) {
      case 'menu':
        await dish();
      case 'clients':
        await customer();
      case 'commandes':
        final changed = await Navigator.push<bool>(
          context,
          MaterialPageRoute(builder: (_) => const OrderEditor()),
        );
        if (changed == true) listKey.currentState?.load(reset: true);
      case 'discussions':
        final c = await pickRecord(context, '/customers', 'Choisir un client');
        if (c != null) await openCustomer(c);
    }
  }

  @override
  Widget build(BuildContext context) {
    final index = paths.indexOf(widget.section);
    final isSettings = index == 4;
    final path = switch (widget.section) {
      'menu' => '/dishes',
      'clients' => '/customers',
      'commandes' => '/orders',
      _ => '/conversations',
    };
    return Scaffold(
      appBar: AppBar(
        title: Text(titles[index]),
        actions: [
          if (!isSettings)
            IconButton(
              onPressed: () => listKey.currentState?.load(reset: true),
              icon: const Icon(Icons.refresh),
              tooltip: 'Actualiser',
            ),
          if (widget.section == 'menu' && admin)
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'menu') {
                  menu();
                } else {
                  context.push('/campaigns');
                }
              },
              itemBuilder: (_) => [
                const PopupMenuItem(
                  value: 'menu',
                  child: Text('Composer le menu du jour'),
                ),
                const PopupMenuItem(
                  value: 'campaigns',
                  child: Text('Menus et campagnes'),
                ),
              ],
            ),
        ],
      ),
      body: isSettings
          ? const SettingsPanel()
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 8,
                  ),
                  child: TextField(
                    key: ValueKey(widget.section),
                    decoration: const InputDecoration(
                      hintText: 'Rechercher',
                      prefixIcon: Icon(Icons.search),
                    ),
                    onSubmitted: (v) => setState(() => q = v),
                  ),
                ),
                if (index == 0 || index == 2)
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Row(
                      children: [
                        for (final s
                            in index == 0
                                ? ['', 'OPEN', 'CLOSED']
                                : [
                                    '',
                                    'PENDING',
                                    'CONFIRMED',
                                    'PREPARING',
                                    'READY',
                                    'DELIVERED',
                                    'CANCELLED',
                                  ])
                          Padding(
                            padding: const EdgeInsets.only(right: 8),
                            child: ChoiceChip(
                              label: Text(s.isEmpty ? 'Tous' : label(s)),
                              selected: status == s,
                              onSelected: (_) => setState(() => status = s),
                            ),
                          ),
                      ],
                    ),
                  ),
                if (index == 0)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: TextField(
                      decoration: const InputDecoration(
                        labelText: 'Catégorie client (vide : toutes)',
                      ),
                      onSubmitted: (v) => setState(() => category = v.trim()),
                    ),
                  ),
                Expanded(
                  child: RemoteList(
                    key: listKey,
                    path: path,
                    query: q,
                    status: status,
                    category: category,
                    tile: (item) {
                      if (index == 0) {
                        return Card(
                          child: ListTile(
                            leading: const CircleAvatar(
                              child: Icon(Icons.person_outline),
                            ),
                            title: Text('${item['name']}'),
                            subtitle: Text(
                              '${item['last_message'] ?? 'Aucun message'}\n${dateText(item['updated_at'])}',
                              maxLines: 3,
                              overflow: TextOverflow.ellipsis,
                            ),
                            isThreeLine: true,
                            trailing: (item['unread'] as num) > 0
                                ? Badge(
                                    label: Text('${item['unread']}'),
                                    child: const Icon(
                                      Icons.chat_bubble_outline,
                                    ),
                                  )
                                : null,
                            onTap: () =>
                                context.push('/discussions/${item['id']}'),
                          ),
                        );
                      }
                      if (index == 1) {
                        return Card(
                          child: ListTile(
                            leading: item['imageUrl'] != null
                                ? DishImage(path: item['imageUrl'] as String)
                                : Icon(
                                    item['available'] == true
                                        ? Icons.restaurant
                                        : Icons.block,
                                  ),
                            title: Text('${item['name']}'),
                            subtitle: Text(
                              '${item['price']} FCFA • ${item['category']}\n${item['available'] == true ? 'Disponible' : 'Indisponible'}',
                            ),
                            isThreeLine: true,
                            trailing: admin
                                ? PopupMenuButton<String>(
                                    onSelected: (v) => dishAction(item, v),
                                    itemBuilder: (_) => [
                                      const PopupMenuItem(
                                        value: 'edit',
                                        child: Text('Modifier'),
                                      ),
                                      const PopupMenuItem(
                                        value: 'image',
                                        child: Text(
                                          'Ajouter ou remplacer l’image',
                                        ),
                                      ),
                                      const PopupMenuItem(
                                        value: 'availability',
                                        child: Text('Changer la disponibilité'),
                                      ),
                                      const PopupMenuItem(
                                        value: 'delete',
                                        child: Text('Supprimer'),
                                      ),
                                    ],
                                  )
                                : null,
                          ),
                        );
                      }
                      if (index == 2) {
                        return Card(
                          child: ListTile(
                            leading: const Icon(Icons.receipt_long),
                            title: Text(
                              '${item['customer_name']} • ${item['total']} FCFA',
                            ),
                            subtitle: Text(
                              '${label(item['status'])} • ${dateText(item['created_at'])}\n${item['paid'] == true ? 'Paiement enregistré' : 'Paiement non enregistré'}',
                            ),
                            onTap: () async {
                              await Navigator.push<void>(
                                context,
                                MaterialPageRoute(
                                  builder: (_) =>
                                      OrderDetail(id: item['id'] as String),
                                ),
                              );
                              listKey.currentState?.load(reset: true);
                            },
                          ),
                        );
                      }
                      return Card(
                        child: ListTile(
                          leading: const CircleAvatar(
                            child: Icon(Icons.person_outline),
                          ),
                          title: Text('${item['name']}'),
                          subtitle: Text(
                            '${item['phone']} • ${item['category']}',
                          ),
                          trailing: item['unsubscribed'] == true
                              ? const Icon(Icons.notifications_off_outlined)
                              : null,
                          onTap: () => openCustomer(item),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
      floatingActionButton: !isSettings && (index != 1 || admin)
          ? FloatingActionButton.extended(
              onPressed: () async {
                try {
                  await add();
                } catch (e) {
                  if (context.mounted) showError(context, e);
                }
              },
              icon: const Icon(Icons.add),
              label: const Text('Ajouter'),
            )
          : null,
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) => context.go('/${paths[i]}'),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.chat_bubble_outline),
            label: 'Discussions',
          ),
          NavigationDestination(
            icon: Icon(Icons.restaurant_menu),
            label: 'Menu',
          ),
          NavigationDestination(
            icon: Icon(Icons.receipt_long_outlined),
            label: 'Commandes',
          ),
          NavigationDestination(
            icon: Icon(Icons.people_outline),
            label: 'Clients',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            label: 'Paramètres',
          ),
        ],
      ),
    );
  }
}
