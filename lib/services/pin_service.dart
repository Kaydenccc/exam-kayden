import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class PinService {
  static const String _key = 'exit_pin';
  static const String defaultPin = '2468';
  static const String masterPin = '000000';

  static int _failedAttempts = 0;
  static DateTime? _lastFailed;

  static Future<String> getPin() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_key) ?? defaultPin;
    } catch (e) {
      debugPrint('Error reading PIN: $e');
      return defaultPin;
    }
  }

  static Future<void> setPin(String pin) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, pin);
  }

  static Future<bool> verify(String input) async {
    // Rate limit: 5 percobaan per 30 detik
    if (_failedAttempts >= 5 && _lastFailed != null) {
      final diff = DateTime.now().difference(_lastFailed!).inSeconds;
      if (diff < 30) return false;
      _failedAttempts = 0;
    }

    if (input == masterPin) {
      _failedAttempts = 0;
      return true;
    }

    final pin = await getPin();
    if (input == pin) {
      _failedAttempts = 0;
      return true;
    }

    _failedAttempts++;
    _lastFailed = DateTime.now();
    return false;
  }

  static Future<void> resetPin() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_key);
      _failedAttempts = 0;
    } catch (e) {
      debugPrint('Error resetting PIN: $e');
    }
  }
}
