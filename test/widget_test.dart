import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:restaurant/app.dart';
import 'package:restaurant/core/api.dart';

void main() {
  testWidgets(
    'Accès fermé sans serveur ni session, aucune conversation inventée',
    (tester) async {
      await tester.pumpWidget(const ProviderScope(child: RestaurantApp()));
      await tester.pumpAndSettle();
      expect(find.text('Se connecter'), findsOneWidget);
      expect(find.textContaining('Adresse serveur manquante'), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull,
      );
      expect(find.text('Flutter Demo'), findsNothing);
    },
  );
  test('Statuts Meta restent distincts', () {
    expect(label('ACCEPTED'), 'Accepté par Meta');
    expect(label('UNKNOWN'), 'Envoi non confirmé');
    expect(label('READ'), 'Lu');
  });
  test('Identifiants de requête UUID uniques', () {
    final ids = List.generate(1000, (_) => requestId());
    expect(ids.toSet().length, 1000);
    expect(
      ids.every(
        (id) => RegExp(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
        ).hasMatch(id),
      ),
      isTrue,
    );
  });
  test('Dates au fuseau Ouagadougou UTC', () {
    expect(dateText('2026-10-21T13:15:00+02:00'), '21/10/2026 11:15');
    expect(dateText(null), '');
  });
}
