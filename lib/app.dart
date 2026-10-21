import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'core/api.dart';
import 'features/workspace.dart';
import 'features/conversation.dart';
import 'features/settings.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    initialLocation: '/discussions',
    redirect: (context, state) {
      final loggedIn = ref.read(authProvider).asData?.value != null;
      if (!loggedIn) return state.matchedLocation == '/login' ? null : '/login';
      return state.matchedLocation == '/login' ? '/discussions' : null;
    },
    routes: [
      GoRoute(path: '/login', builder: (_, _) => const LoginPage()),
      GoRoute(
        path: '/discussions/:id',
        builder: (_, state) =>
            ConversationPage(id: state.pathParameters['id']!),
      ),
      GoRoute(path: '/whatsapp', builder: (_, _) => const WhatsAppPage()),
      GoRoute(path: '/campaigns', builder: (_, _) => const CampaignsPage()),
      for (final path in [
        'discussions',
        'menu',
        'commandes',
        'clients',
        'parametres',
      ])
        GoRoute(
          path: '/$path',
          builder: (_, _) => WorkspacePage(section: path),
        ),
    ],
  );
  ref.listen(authProvider, (_, _) => router.refresh());
  ref.onDispose(router.dispose);
  return router;
});

class RestaurantApp extends ConsumerWidget {
  const RestaurantApp({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
    title: 'Restaurant Connect BF',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff13795b)),
      scaffoldBackgroundColor: const Color(0xfff6f8f5),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Color(0xfff6f8f5),
        centerTitle: false,
      ),
    ),
    routerConfig: ref.watch(routerProvider),
  );
}

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});
  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final email = TextEditingController(), password = TextEditingController();
  bool busy = false;
  String? error;
  @override
  void dispose() {
    email.dispose();
    password.dispose();
    super.dispose();
  }

  Future<void> login() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref.read(authProvider.notifier).login(email.text, password.text);
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final configured = ref.watch(apiProvider).configured;
    final auth = ref.watch(authProvider);
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Icon(
                  Icons.restaurant_rounded,
                  size: 64,
                  color: Color(0xff13795b),
                ),
                const SizedBox(height: 20),
                Text(
                  'Restaurant\nConnect BF',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineLarge,
                ),
                const SizedBox(height: 12),
                const Text(
                  'Votre restaurant, vos clients, une seule équipe.',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 32),
                if (!configured)
                  const Text(
                    'Adresse serveur manquante ou non sécurisée. Configurez API_BASE_URL à la compilation.',
                  ),
                if (auth.isLoading) const LinearProgressIndicator(),
                if (auth.hasError) ...[
                  Text(errorText(auth.error!)),
                  TextButton(
                    onPressed: () => ref.invalidate(authProvider),
                    child: const Text('Rétablir la session'),
                  ),
                ],
                TextField(
                  controller: email,
                  keyboardType: TextInputType.emailAddress,
                  autofillHints: const [AutofillHints.username],
                  decoration: const InputDecoration(
                    labelText: 'Adresse e-mail',
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: password,
                  obscureText: true,
                  autofillHints: const [AutofillHints.password],
                  decoration: const InputDecoration(labelText: 'Mot de passe'),
                  onSubmitted: (_) {
                    if (!busy && configured) login();
                  },
                ),
                const SizedBox(height: 16),
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 16),
                    child: Text(
                      error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                FilledButton(
                  onPressed: busy || !configured ? null : login,
                  child: Text(busy ? 'Connexion…' : 'Se connecter'),
                ),
                const SizedBox(height: 24),
                const Text(
                  'Accès réservé à l’équipe • Africa/Ouagadougou',
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
