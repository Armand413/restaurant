import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:restaurant/features/settings.dart';

void main() {
  Future<void> open(WidgetTester tester, Map<String, dynamic> status) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [whatsAppStatusProvider.overrideWith((ref) async => status)],
        child: const MaterialApp(home: WhatsAppPage()),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('Configuration absente : aucune connexion Meta annoncée', (
    tester,
  ) async {
    await open(tester, {
      'configured': false,
      'onboardingAvailable': false,
      'missingConfiguration': ['META_APP_ID manquant'],
      'reason': 'Application Meta à préparer',
      'pendingEvents': 0,
      'lastSync': [],
    });
    expect(find.text('Continuer avec Meta'), findsNothing);
    expect(find.text('• META_APP_ID manquant'), findsOneWidget);
    expect(
      find.textContaining('Connexion WhatsApp non activée'),
      findsOneWidget,
    );
  });

  testWidgets('Serveur prêt : bouton officiel disponible', (tester) async {
    await open(tester, {
      'configured': false,
      'onboardingAvailable': true,
      'missingConfiguration': [],
      'reason': 'Éligibilité décidée par Meta',
      'pendingEvents': 0,
      'lastSync': [],
    });
    expect(find.text('Continuer avec Meta'), findsOneWidget);
    expect(find.textContaining('Numéro associé'), findsNothing);
  });
}
