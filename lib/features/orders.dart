import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api.dart';
import 'widgets.dart';

class OrderEditor extends ConsumerStatefulWidget {
  const OrderEditor({super.key});
  @override
  ConsumerState<OrderEditor> createState() => _OrderEditorState();
}

class _OrderEditorState extends ConsumerState<OrderEditor> {
  final creationKey = requestId();
  Json? customer;
  final items = <Json>[];
  String fulfillment = 'PICKUP';
  final address = TextEditingController(),
      instructions = TextEditingController();
  bool busy = false;
  String? error;
  @override
  void dispose() {
    address.dispose();
    instructions.dispose();
    super.dispose();
  }

  Future<void> add() async {
    final dish = await pickRecord(context, '/dishes', 'Choisir un plat');
    if (dish == null || !mounted) return;
    if (dish['available'] != true) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Ce plat est indisponible.')),
      );
      return;
    }
    setState(() {
      final index = items.indexWhere((i) => i['id'] == dish['id']);
      if (index < 0) {
        items.add({...dish, 'quantity': 1});
      } else if ((items[index]['quantity'] as int) < 100) {
        items[index]['quantity'] = (items[index]['quantity'] as int) + 1;
      }
    });
  }

  Future<void> save() async {
    if (customer == null ||
        items.isEmpty ||
        (fulfillment == 'DELIVERY' && address.text.trim().isEmpty)) {
      setState(
        () => error = 'Choisissez un client, au moins un article et l’adresse de livraison si nécessaire.',
      );
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await ref
          .read(apiProvider)
          .call(
            '/orders',
            method: 'POST',
            data: {
              'requestKey': creationKey,
              'customerId': customer!['id'],
              'fulfillment': fulfillment,
              'address': address.text.trim(),
              'instructions': instructions.text.trim(),
              'items': items
                  .map((i) => {'dishId': i['id'], 'quantity': i['quantity']})
                  .toList(),
            },
          );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Commande enregistrée : ${result['total']} FCFA. Paiement non enregistré.',
            ),
          ),
        );
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final estimate = items.fold<int>(
      0,
      (sum, i) => sum + (i['price'] as int) * (i['quantity'] as int),
    );
    return PopScope(
      canPop: !busy,
      child: Scaffold(
        appBar: AppBar(title: const Text('Nouvelle commande')),
        body: AbsorbPointer(
          absorbing: busy,
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              OutlinedButton.icon(
                onPressed: () async {
                  final c = await pickRecord(
                    context,
                    '/customers',
                    'Choisir un client',
                  );
                  if (c != null && mounted) setState(() => customer = c);
                },
                icon: const Icon(Icons.person_outline),
                label: Text(
                  customer?['name'] as String? ?? 'Choisir le client',
                ),
              ),
              const SizedBox(height: 20),
              DropdownButtonFormField<String>(
                initialValue: fulfillment,
                decoration: const InputDecoration(
                  labelText: 'Mode de réception',
                ),
                items: const [
                  DropdownMenuItem(
                    value: 'PICKUP',
                    child: Text('Retrait sur place'),
                  ),
                  DropdownMenuItem(value: 'DELIVERY', child: Text('Livraison')),
                ],
                onChanged: (v) => setState(() => fulfillment = v!),
              ),
              const SizedBox(height: 16),
              if (fulfillment == 'DELIVERY') ...[
                TextField(
                  controller: address,
                  maxLines: 2,
                  decoration: const InputDecoration(
                    labelText: 'Adresse de livraison',
                  ),
                ),
                const SizedBox(height: 16),
              ],
              TextField(
                controller: instructions,
                maxLines: 2,
                decoration: const InputDecoration(
                  labelText: 'Instructions (facultatif)',
                ),
              ),
              const SizedBox(height: 24),
              for (final item in items)
                Card(
                  child: Column(
                    children: [
                      ListTile(
                        title: Text('${item['name']}'),
                        subtitle: Text('${item['price']} FCFA par unité'),
                        trailing: IconButton(
                          onPressed: () => setState(() => items.remove(item)),
                          icon: const Icon(Icons.delete_outline),
                        ),
                      ),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          IconButton(
                            onPressed: item['quantity'] > 1
                                ? () => setState(() => item['quantity']--)
                                : null,
                            icon: const Icon(Icons.remove),
                          ),
                          Text('${item['quantity']}'),
                          IconButton(
                            onPressed: item['quantity'] < 100
                                ? () => setState(() => item['quantity']++)
                                : null,
                            icon: const Icon(Icons.add),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              OutlinedButton.icon(
                onPressed: add,
                icon: const Icon(Icons.add),
                label: const Text('Ajouter un article'),
              ),
              const SizedBox(height: 20),
              Text(
                'Estimation : $estimate FCFA',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const Text(
                'Le serveur vérifie les prix et calcule le total définitif.',
              ),
              const SizedBox(height: 24),
              if (error != null)
                Text(
                  error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              FilledButton(
                onPressed: save,
                child: Text(busy ? 'Enregistrement…' : 'Créer la commande'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class OrderDetail extends ConsumerStatefulWidget {
  const OrderDetail({super.key, required this.id});
  final String id;
  @override
  ConsumerState<OrderDetail> createState() => _OrderDetailState();
}

class _OrderDetailState extends ConsumerState<OrderDetail> {
  Json? data;
  Object? error;
  bool busy = false;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final next = Json.from(
        await ref.read(apiProvider).call('/orders/${widget.id}') as Map,
      );
      if (mounted) {
        setState(() {
          data = next;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e);
    }
  }

  Future<void> status(String value) async {
    if (!await confirm(
      context,
      'Passer à « ${label(value)} » ?',
      'Cette modification sera enregistrée dans l’historique.',
    )) {
      return;
    }
    setState(() => busy = true);
    try {
      await ref
          .read(apiProvider)
          .call(
            '/orders/${widget.id}/status',
            method: 'POST',
            data: {'status': value},
          );
      await load();
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final order = data?['order'] as Map?;
    final choices = switch (order?['status']) {
      'PENDING' => ['CONFIRMED', 'CANCELLED'],
      'CONFIRMED' => ['PREPARING', 'CANCELLED'],
      'PREPARING' => ['READY', 'CANCELLED'],
      'READY' => ['DELIVERED', 'CANCELLED'],
      _ => <String>[],
    };
    return Scaffold(
      appBar: AppBar(
        title: const Text('Détail de commande'),
        actions: [IconButton(onPressed: load, icon: const Icon(Icons.refresh))],
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          if (error != null) Text(errorText(error!)),
          if (data == null && error == null) const LinearProgressIndicator(),
          if (order != null) ...[
            Text(
              '${order['total']} FCFA',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            Text(label(order['status'])),
            Text(
              order['paid'] == true
                  ? 'Paiement enregistré'
                  : 'Paiement non enregistré',
            ),
            Text(
              order['fulfillment'] == 'DELIVERY'
                  ? 'Livraison : ${order['address']}'
                  : 'Retrait sur place',
            ),
            Text('${order['instructions']}'),
            const Divider(),
            for (final item in data!['items'] as List)
              ListTile(
                title: Text('${item['name']} × ${item['quantity']}'),
                trailing: Text('${item['unit_price']} FCFA / unité'),
              ),
            for (final value in choices)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: OutlinedButton(
                  onPressed: busy ? null : () => status(value),
                  child: Text(label(value)),
                ),
              ),
            const SizedBox(height: 24),
            Text('Historique', style: Theme.of(context).textTheme.titleLarge),
            for (final event in data!['history'] as List)
              ListTile(
                title: Text(label(event['status'])),
                subtitle: Text(dateText(event['changed_at'])),
              ),
          ],
        ],
      ),
    );
  }
}
