import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:window_manager/window_manager.dart';

class KioskService {
  static const _channel = MethodChannel('id.sekolah.pengunci_ujian/kiosk');

  static Future<void> enterKiosk() async {
    try { await WakelockPlus.enable(); } catch (e) { debugPrint('Wakelock enable: $e'); }
    try { await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky); } catch (e) { debugPrint('SystemUI: $e'); }

    if (Platform.isAndroid) {
      try {
        await _channel.invokeMethod('startKiosk');
      } on PlatformException catch (e) {
        debugPrint('Native startKiosk failed: ${e.message}');
      }
    }

    if (Platform.isWindows) {
      try {
        await windowManager.setFullScreen(true);
        await windowManager.setAlwaysOnTop(true);
        await windowManager.setPreventClose(true);
        await _channel.invokeMethod('startKiosk');
      } catch (e) {
        debugPrint('Windows kiosk: $e');
      }
    }
  }

  static Future<void> exitKiosk() async {
    try { await WakelockPlus.disable(); } catch (e) { debugPrint('Wakelock disable: $e'); }
    try { await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge); } catch (e) { debugPrint('SystemUI reset: $e'); }

    if (Platform.isAndroid) {
      try {
        await _channel.invokeMethod('stopKiosk');
      } on PlatformException catch (e) {
        debugPrint('Native stopKiosk failed: ${e.message}');
      }
    }

    if (Platform.isWindows) {
      try {
        await windowManager.setAlwaysOnTop(false);
        await windowManager.setFullScreen(false);
        await windowManager.setPreventClose(false);
        await _channel.invokeMethod('stopKiosk');
      } catch (e) {
        debugPrint('Windows kiosk exit: $e');
      }
    }
  }
}
