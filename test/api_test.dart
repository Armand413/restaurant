import 'dart:typed_data';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:restaurant/core/api.dart';

class TestAdapter implements HttpClientAdapter {
  TestAdapter(this.answer);
  final ResponseBody Function(RequestOptions) answer;
  RequestOptions? last;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    last = options;
    return answer(options);
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(
    () => FlutterSecureStorage.setMockInitialValues({
      'session': 'isolated-test-token',
    }),
  );
  ResponseBody response(Object body, int status) => ResponseBody.fromString(
    jsonEncode(body),
    status,
    headers: {
      'content-type': ['application/json'],
    },
  );
  test('Repository transmet session, recherche et pagination', () async {
    final adapter = TestAdapter(
      (_) => response([
        {'id': 'test', 'name': 'Client de test isolé'},
      ], 200),
    );
    final dio = Dio(BaseOptions(baseUrl: 'https://test.invalid'))
      ..httpClientAdapter = adapter;
    final data = await Api(client: dio)
        .list('/customers', page: 2, q: 'recherche');
    expect(data.single['id'], 'test');
    expect(adapter.last!.queryParameters['page'], 2);
    expect(adapter.last!.queryParameters['q'], 'recherche');
    expect(
      adapter.last!.headers['Authorization'],
      'Bearer isolated-test-token',
    );
  });
  test('Repository décode la pagination du catalogue', () async {
    final adapter = TestAdapter(
      (_) => response({
        'content': [
          {'id': 'test', 'price': 2500},
        ],
      }, 200),
    );
    final dio = Dio(BaseOptions(baseUrl: 'https://test.invalid'))
      ..httpClientAdapter = adapter;
    expect((await Api(client: dio).list('/dishes')).single['price'], 2500);
  });
  test(
    'Erreur serveur ne devient jamais une liste vide ou un succès',
    () async {
      final adapter = TestAdapter(
        (_) => response({'message': 'WhatsApp non configuré'}, 503),
      );
      final dio = Dio(BaseOptions(baseUrl: 'https://test.invalid'))
        ..httpClientAdapter = adapter;
      await expectLater(
        Api(client: dio).list('/conversations'),
        throwsA(isA<DioException>()),
      );
    },
  );
  test('État Riverpod sans configuration ne crée pas de session', () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    expect(await container.read(authProvider.future), isNull);
  });
}
