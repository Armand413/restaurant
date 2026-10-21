import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

typedef Json = Map<String, dynamic>;
const apiUrl = String.fromEnvironment('API_BASE_URL');
final apiProvider = Provider<Api>(
  (ref) => Api(onUnauthorized: () => ref.invalidate(authProvider)),
);

class Api {
  Api({Dio? client, FlutterSecureStorage? storage, this.onUnauthorized})
    : dio =
          client ??
          Dio(
            BaseOptions(
              baseUrl: apiUrl,
              connectTimeout: const Duration(seconds: 10),
              receiveTimeout: const Duration(seconds: 30),
              sendTimeout: const Duration(seconds: 15),
            ),
          ),
      storage = storage ?? const FlutterSecureStorage();
  final Dio dio;
  final FlutterSecureStorage storage;
  final VoidCallback? onUnauthorized;
  bool get configured {
    final uri = Uri.tryParse(apiUrl);
    return uri != null &&
        uri.hasAuthority &&
        (uri.scheme == 'https' || (!kReleaseMode && uri.scheme == 'http'));
  }

  Future<dynamic> call(
    String path, {
    String method = 'GET',
    Object? data,
    Map<String, dynamic>? query,
  }) async {
    final token = await storage.read(key: 'session');
    try {
      final response = await dio.request<dynamic>(
        '/api$path',
        data: data,
        queryParameters: query,
        options: Options(
          method: method,
          headers: {if (token != null) 'Authorization': 'Bearer $token'},
        ),
      );
      return response.data;
    } on DioException catch (e) {
      if (e.response?.statusCode == 401 &&
          path != '/auth/login' &&
          path != '/auth/me') {
        await storage.delete(key: 'session');
        onUnauthorized?.call();
      }
      rethrow;
    }
  }

  Future<List<Json>> list(
    String path, {
    int page = 0,
    String q = '',
    String status = '',
    String category = '',
  }) async {
    final data = await call(
      path,
      query: {'page': page, 'q': q, 'status': status, 'category': category},
    );
    final items = data is List ? data : (data as Map)['content'] as List;
    return items.map((e) => Json.from(e as Map)).toList();
  }
}

String errorText(Object error) {
  if (error is DioException) {
    if (error.response?.statusCode == 401) {
      return 'Session expirée ou identifiants invalides. Reconnectez-vous.';
    }
    if (error.response?.statusCode == 403) {
      return 'Votre rôle ne permet pas cette opération.';
    }
    if (error.response?.statusCode == 429) {
      return 'Trop de requêtes. Réessayez dans une minute.';
    }
    final data = error.response?.data;
    if (data is Map && data['message'] is String) {
      return data['message'] as String;
    }
    return 'Serveur indisponible ou connexion interrompue. L’opération n’est pas confirmée.';
  }
  return 'Impossible de terminer cette opération. Réessayez après vérification.';
}

String requestId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final s = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}';
}

final authProvider = AsyncNotifierProvider<Auth, Json?>(Auth.new);

class Auth extends AsyncNotifier<Json?> {
  @override
  Future<Json?> build() async {
    final api = ref.read(apiProvider);
    if (!api.configured || await api.storage.read(key: 'session') == null) {
      return null;
    }
    try {
      return Json.from(await api.call('/auth/me') as Map);
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        await api.storage.delete(key: 'session');
        return null;
      }
      rethrow;
    }
  }

  Future<void> login(String email, String password) async {
    final api = ref.read(apiProvider);
    final data = Json.from(
      await api.call(
        '/auth/login',
        method: 'POST',
        data: {'email': email.trim(), 'password': password},
      ) as Map,
    );
    await api.storage.write(key: 'session', value: data['token'] as String);
    state = AsyncData(Json.from(await api.call('/auth/me') as Map));
  }

  Future<void> logout() async {
    final api = ref.read(apiProvider);
    await api.call('/auth/logout', method: 'POST');
    await api.storage.delete(key: 'session');
    state = const AsyncData(null);
  }
}

const labels = <String, String>{
  'PENDING': 'En attente',
  'ACCEPTED': 'Accepté par Meta',
  'UNKNOWN': 'Envoi non confirmé',
  'SENT': 'Envoyé',
  'DELIVERED': 'Livré',
  'READ': 'Lu',
  'FAILED': 'Échec',
  'RECEIVED': 'Reçu',
  'CONFIRMED': 'Confirmée',
  'PREPARING': 'En préparation',
  'READY': 'Prête',
  'CANCELLED': 'Annulée',
  'OPEN': 'Ouverte',
  'CLOSED': 'Fermée',
  'DRAFT': 'Brouillon',
  'RUNNING': 'En cours',
  'COMPLETED': 'Traitement terminé',
  'EXCLUDED': 'Exclu',
  'UNVERIFIED': 'En attente de confirmation',
};
String label(Object? value) =>
    labels[value] ?? value?.toString() ?? 'En attente de confirmation';
String dateText(Object? value) {
  final d = DateTime.tryParse(value?.toString() ?? '')?.toUtc();
  if (d == null) return '';
  String n(int x) => x.toString().padLeft(2, '0');
  return '${n(d.day)}/${n(d.month)}/${d.year} ${n(d.hour)}:${n(d.minute)}';
}
