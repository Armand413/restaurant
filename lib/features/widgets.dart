import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api.dart';

class DishImage extends ConsumerStatefulWidget {
  const DishImage({super.key, required this.path});
  final String path;
  @override
  ConsumerState<DishImage> createState() => _DishImageState();
}

class _DishImageState extends ConsumerState<DishImage> {
  late final Future<String?> token = ref
      .read(apiProvider)
      .storage
      .read(key: 'session');
  @override
  Widget build(BuildContext context) => FutureBuilder<String?>(
    future: token,
    builder: (context, snapshot) {
      if (!snapshot.hasData ||
          !RegExp(r'^/api/media/[0-9a-f-]{36}\.(png|jpg)$')
              .hasMatch(widget.path)) {
        return const Icon(Icons.restaurant);
      }
      return ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: Image.network(
          '$apiUrl${widget.path}',
          width: 48,
          height: 48,
          fit: BoxFit.cover,
          headers: {'Authorization': 'Bearer ${snapshot.data}'},
          errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined),
        ),
      );
    },
  );
}

Future<bool> confirm(BuildContext context, String title, String body) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirmer'),
          ),
        ],
      ),
    ) ??
    false;

void showError(BuildContext context, Object e) => ScaffoldMessenger.of(context)
    .showSnackBar(
      SnackBar(
        content: Text(errorText(e)),
        duration: const Duration(seconds: 7),
      ),
    );

class FieldSpec {
  const FieldSpec(
    this.key,
    this.label, {
    this.number = false,
    this.required = true,
    this.lines = 1,
  });
  final String key, label;
  final bool number, required;
  final int lines;
}

Future<bool> editForm(
  BuildContext context, {
  required String title,
  required List<FieldSpec> fields,
  Json initial = const {},
  required Future<void> Function(Json) save,
}) async =>
    await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) =>
          _Editor(title: title, fields: fields, initial: initial, save: save),
    ) ??
    false;

class _Editor extends StatefulWidget {
  const _Editor({
    required this.title,
    required this.fields,
    required this.initial,
    required this.save,
  });
  final String title;
  final List<FieldSpec> fields;
  final Json initial;
  final Future<void> Function(Json) save;
  @override
  State<_Editor> createState() => _EditorState();
}

class _EditorState extends State<_Editor> {
  final form = GlobalKey<FormState>();
  late final controllers = {
    for (final f in widget.fields)
      f.key: TextEditingController(
        text: widget.initial[f.key]?.toString() ?? '',
      ),
  };
  bool busy = false;
  String? error;
  @override
  void dispose() {
    for (final c in controllers.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> save() async {
    if (!form.currentState!.validate()) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await widget.save({
        for (final f in widget.fields)
          f.key: f.number
              ? int.parse(controllers[f.key]!.text.trim())
              : controllers[f.key]!.text.trim(),
      });
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !busy,
    child: AlertDialog(
      title: Text(widget.title),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Form(
            key: form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final f in widget.fields)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 16),
                    child: TextFormField(
                      obscureText: f.key == 'password',
                      autocorrect: f.key != 'password',
                      controller: controllers[f.key],
                      enabled: !busy,
                      maxLines: f.lines,
                      keyboardType: f.number
                          ? TextInputType.number
                          : TextInputType.text,
                      decoration: InputDecoration(labelText: f.label),
                      validator: (v) {
                        if (f.required && (v == null || v.trim().isEmpty)) {
                          return 'Champ requis';
                        }
                        if (f.number &&
                            (int.tryParse(v ?? '') == null ||
                                int.parse(v!) < 0)) {
                          return 'Entier positif requis';
                        }
                        return null;
                      },
                    ),
                  ),
                if (error != null)
                  Text(
                    error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: busy ? null : () => Navigator.pop(context, false),
          child: const Text('Annuler'),
        ),
        FilledButton(
          onPressed: busy ? null : save,
          child: Text(busy ? 'Enregistrement…' : 'Enregistrer'),
        ),
      ],
    ),
  );
}

class RemoteList extends ConsumerStatefulWidget {
  const RemoteList({
    super.key,
    required this.path,
    required this.tile,
    this.query = '',
    this.status = '',
    this.category = '',
  });
  final String path, query, status, category;
  final Widget Function(Json) tile;
  @override
  ConsumerState<RemoteList> createState() => RemoteListState();
}

class RemoteListState extends ConsumerState<RemoteList> {
  List<Json> items = [];
  int page = 0, generation = 0;
  bool busy = false, more = true;
  Object? error;
  @override
  void initState() {
    super.initState();
    load(reset: true);
  }

  @override
  void didUpdateWidget(RemoteList oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.path != widget.path ||
        oldWidget.query != widget.query ||
        oldWidget.status != widget.status ||
        oldWidget.category != widget.category) {
      load(reset: true);
    }
  }

  Future<void> load({bool reset = false}) async {
    if (busy && !reset) return;
    final current = ++generation;
    setState(() {
      busy = true;
      error = null;
      if (reset) {
        page = 0;
        items = [];
        more = true;
      }
    });
    try {
      final next = await ref
          .read(apiProvider)
          .list(
            widget.path,
            page: page,
            q: widget.query,
            status: widget.status,
            category: widget.category,
          );
      if (mounted && current == generation) {
        setState(() {
          items.addAll(next);
          page++;
          more = next.length == 30;
        });
      }
    } catch (e) {
      if (mounted && current == generation) setState(() => error = e);
    } finally {
      if (mounted && current == generation) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: () => load(reset: true),
    child: ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 100),
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        if (busy) const LinearProgressIndicator(),
        if (error != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  Text(errorText(error!)),
                  TextButton(
                    onPressed: () => load(reset: items.isEmpty),
                    child: const Text('Réessayer'),
                  ),
                ],
              ),
            ),
          ),
        if (items.isEmpty && !busy && error == null)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 80),
            child: Column(
              children: [
                Icon(Icons.inbox_outlined, size: 48),
                SizedBox(height: 16),
                Text(
                  'Aucun élément pour le moment.',
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        for (final item in items) widget.tile(item),
        if (more && !busy && error == null && items.isNotEmpty)
          TextButton(onPressed: load, child: const Text('Charger la suite')),
      ],
    ),
  );
}

Future<Json?> pickRecord(BuildContext context, String path, String title) =>
    showDialog<Json>(
      context: context,
      builder: (_) => _Picker(path: path, title: title),
    );

class _Picker extends StatefulWidget {
  const _Picker({required this.path, required this.title});
  final String path, title;
  @override
  State<_Picker> createState() => _PickerState();
}

class _PickerState extends State<_Picker> {
  String query = '';
  @override
  Widget build(BuildContext context) => Dialog(
    child: SizedBox(
      width: 480,
      height: 560,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              widget.title,
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              decoration: const InputDecoration(labelText: 'Rechercher'),
              onSubmitted: (v) => setState(() => query = v),
            ),
          ),
          Expanded(
            child: RemoteList(
              path: widget.path,
              query: query,
              tile: (item) => ListTile(
                title: Text('${item['name'] ?? item['title']}'),
                subtitle: Text('${item['phone'] ?? item['price'] ?? ''}'),
                onTap: () => Navigator.pop(context, item),
              ),
            ),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Annuler'),
          ),
        ],
      ),
    ),
  );
}
