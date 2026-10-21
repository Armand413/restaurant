import 'dart:async';

import 'package:dio/dio.dart';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api.dart';
import 'widgets.dart';

class ConversationPage extends ConsumerStatefulWidget {
  const ConversationPage({super.key, required this.id});
  final String id;
  @override
  ConsumerState<ConversationPage> createState() => _ConversationPageState();
}

class _ConversationPageState extends ConsumerState<ConversationPage>
    with WidgetsBindingObserver {
  List<Json> messages = [];
  final text = TextEditingController();
  Timer? timer;
  bool busy = false, loading = false, more = true, foreground = true;
  Object? error;
  int pages = 1;
  String? pendingKey, pendingBody;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    load();
    timer = Timer.periodic(const Duration(seconds: 5), (_) {
      if (foreground) load(silent: true);
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
    if (foreground) load(silent: true);
  }

  @override
  void dispose() {
    timer?.cancel();
    text.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> load({bool silent = false}) async {
    if (loading) return;
    loading = true;
    if (!silent && mounted) setState(() {});
    try {
      final api = ref.read(apiProvider);
      final result = <Json>[];
      List<Json> last = [];
      for (int page = 0; page < pages; page++) {
        last = await api.list(
          '/conversations/${widget.id}/messages',
          page: page,
        );
        result.addAll(last);
      }
      await api.call('/conversations/${widget.id}/read', method: 'POST');
      if (mounted) {
        setState(() {
          messages = {for (final m in result) m['id']: m}.values.toList();
          more = last.length == 50;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e);
    } finally {
      loading = false;
      if (mounted && !silent) setState(() {});
    }
  }

  Future<void> send() async {
    final body = text.text.trim();
    if (body.isEmpty || busy) return;
    if (pendingBody != null && pendingBody != body) {
      showError(context, StateError('Vérifiez le message précédent'));
      return;
    }
    pendingKey ??= requestId();
    pendingBody = body;
    setState(() => busy = true);
    try {
      final message = Json.from(
        await ref
                .read(apiProvider)
                .call(
                  '/conversations/${widget.id}/messages',
                  method: 'POST',
                  data: {'requestKey': pendingKey, 'body': body},
                )
            as Map,
      );
      pendingKey = null;
      pendingBody = null;
      text.clear();
      if (mounted) {
        setState(() {
          messages.removeWhere((m) => m['id'] == message['id']);
          messages.insert(0, message);
        });
      }
      await load(silent: true);
    } catch (e) {
      if (e is DioException &&
          e.response != null &&
          (e.response!.statusCode ?? 500) < 500) {
        pendingKey = null;
        pendingBody = null;
      }
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Conversation'),
      actions: [
        IconButton(
          onPressed: () => load(),
          icon: const Icon(Icons.refresh),
          tooltip: 'Actualiser',
        ),
      ],
    ),
    body: Column(
      children: [
        if (loading) const LinearProgressIndicator(),
        if (error != null)
          MaterialBanner(
            content: Text(errorText(error!)),
            actions: [
              TextButton(
                onPressed: () => load(),
                child: const Text('Réessayer'),
              ),
            ],
          ),
        Expanded(
          child: messages.isEmpty && !loading
              ? const Center(child: Text('Aucun message reçu ou envoyé.'))
              : ListView.builder(
                  reverse: true,
                  padding: const EdgeInsets.all(16),
                  itemCount: messages.length + (more ? 1 : 0),
                  itemBuilder: (context, index) {
                    if (index == messages.length) {
                      return TextButton(
                        onPressed: loading
                            ? null
                            : () {
                                pages++;
                                load();
                              },
                        child: const Text('Messages plus anciens'),
                      );
                    }
                    final m = messages[index];
                    final outgoing = m['direction'] == 'OUT';
                    return Align(
                      alignment: outgoing
                          ? Alignment.centerRight
                          : Alignment.centerLeft,
                      child: Container(
                        constraints: BoxConstraints(
                          maxWidth: MediaQuery.sizeOf(context).width * .82,
                        ),
                        margin: const EdgeInsets.symmetric(vertical: 4),
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: outgoing
                              ? const Color(0xffd9f0e4)
                              : Colors.white,
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SelectableText('${m['body']}'),
                            const SizedBox(height: 6),
                            Text(
                              '${dateText(m['created_at'])} • ${label(m['status'])}',
                              style: Theme.of(context).textTheme.labelSmall,
                            ),
                            if (m['error_code'] != null)
                              Text(
                                '${m['error_code']}',
                                style: TextStyle(
                                  color: Theme.of(context).colorScheme.error,
                                ),
                              ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
        ),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: Text(
            'Les réponses libres nécessitent un message client reçu dans les dernières 24 heures.',
            style: TextStyle(fontSize: 11),
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    controller: text,
                    readOnly: busy || pendingBody != null,
                    minLines: 1,
                    maxLines: 5,
                    maxLength: 4096,
                    decoration: InputDecoration(
                      hintText: 'Message',
                      counterText: '',
                      helperText: pendingBody != null
                          ? 'Réessayer conserve la même clé d’envoi.'
                          : null,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: busy ? null : send,
                  icon: busy
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.send),
                  tooltip: pendingBody != null
                      ? 'Vérifier / réessayer le même envoi'
                      : 'Envoyer',
                ),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}
