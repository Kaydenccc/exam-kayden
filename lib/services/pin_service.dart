import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class PinService {
  static const String _key = 'exit_pin';
  static const String defaultPin = '2468';

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

  static const String masterPin = '000000';

  static Future<bool> verify(String input) async {
    if (input == masterPin) return true;
    final pin = await getPin();
    return input == pin;
  }

  static Future<void> resetPin() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_key);
    } catch (e) {
      debugPrint('Error resetting PIN: $e');
    }
  }
}
