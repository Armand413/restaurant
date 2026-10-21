import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:restaurant/app.dart';
import 'package:restaurant/core/api.dart';

class TeamSession extends Auth {
  @override
  Future<Json?> build() async => {
    'email': 'test@example.invalid',
    'role': 'EMPLOYEE',
  };
}

class CustomerTransport implements HttpClientAdapter {
  final records = <Json>[];
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    Object response = [];
    if (options.path == '/api/customers' && options.method == 'POST') {
      final body = options.data is String
          ? Json.from(jsonDecode(options.data as String) as Map)
          : Json.from(options.data as Map);
      records.add({...body, 'id': 'test-customer', 'unsubscribed': false});
      response = {'id': 'test-customer'};
    } else if (options.path == '/api/customers') {
      response = records;
    }
    return ResponseBody.fromString(
      jsonEncode(response),
      200,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  testWidgets(
    'Parcours client : formulaire, écriture HTTP, relecture dans la liste',
    (tester) async {
      FlutterSecureStorage.setMockInitialValues({'session': 'test-session'});
      final transport = CustomerTransport();
      final dio = Dio(BaseOptions(baseUrl: 'https://test.invalid'))
        ..httpClientAdapter = transport;
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            apiProvider.overrideWithValue(Api(client: dio)),
            authProvider.overrideWith(TeamSession.new),
          ],
          child: const RestaurantApp(),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.people_outline));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ajouter'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Nom'),
        'Client du test de parcours',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Téléphone international (+226…)'),
        '+22670111111',
      );
      await tester.tap(find.text('Enregistrer'));
      await tester.pumpAndSettle();
      expect(transport.records.single['phone'], '+22670111111');
      expect(find.text('Client du test de parcours'), findsOneWidget);
      expect(find.text('Enregistrer'), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
