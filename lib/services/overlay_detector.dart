import 'dart:async';
import 'package:flutter/services.dart';

class OverlayDetector {
  static const _channel = EventChannel('id.sekolah.pengunci_ujian/overlay');
  static StreamSubscription? _subscription;
  static final _controller = StreamController<bool>.broadcast();

  static Stream<bool> get stream => _controller.stream;
  static bool isObscured = false;

  static void start() {
    _subscription?.cancel();
    _subscription = _channel.receiveBroadcastStream().listen((event) {
      final obscured = event as bool;
      isObscured = obscured;
      _controller.add(obscured);
    });
  }

  static void stop() {
    _subscription?.cancel();
    _subscription = null;
  }
}
