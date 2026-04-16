import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:permission_handler/permission_handler.dart';

import '../services/overlay_detector.dart';
import '../services/pin_service.dart';
import 'exam_webview_screen.dart';
import 'pin_settings_screen.dart';

class ScannerScreen extends StatefulWidget {
  const ScannerScreen({super.key});

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  MobileScannerController? _controller;
  bool _isProcessing = false;
  bool _cameraReady = false;
  bool _overlayDetected = false;
  final TextEditingController _urlController = TextEditingController();
  final List<DateTime> _titleTaps = [];
  StreamSubscription? _overlaySub;

  @override
  void initState() {
    super.initState();
    _initCamera();
    if (Platform.isAndroid) {
      OverlayDetector.start();
      _overlaySub = OverlayDetector.stream.listen((obscured) {
        if (mounted) setState(() => _overlayDetected = obscured);
      });
    }
  }

  Future<void> _initCamera() async {
    try {
      if (Platform.isAndroid) {
        final status = await Permission.camera.request();
        if (!status.isGranted) {
          if (mounted) setState(() => _cameraReady = false);
          return;
        }
      }

      final ctrl = MobileScannerController(
        detectionSpeed: DetectionSpeed.normal,
        facing: CameraFacing.back,
      );

      if (mounted) {
        _controller = ctrl;
        setState(() => _cameraReady = true);
      } else {
        ctrl.dispose();
      }
    } catch (e) {
      debugPrint('Camera init error: $e');
      if (mounted) setState(() => _cameraReady = false);
    }
  }

  @override
  void dispose() {
    _overlaySub?.cancel();
    _controller?.dispose();
    _urlController.dispose();
    super.dispose();
  }

  void _onDetect(BarcodeCapture capture) {
    if (_isProcessing) return;
    final value = capture.barcodes.firstOrNull?.rawValue;
    if (value == null || value.isEmpty) return;
    _launchExam(value);
  }

  Future<void> _launchExam(String raw) async {
    final url = raw.trim();
    if (!_isValidUrl(url)) {
      _showSnack('URL tidak valid. Pastikan diawali http:// atau https://');
      return;
    }

    setState(() => _isProcessing = true);
    try { await _controller?.stop(); } catch (_) {}

    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ExamWebViewScreen(url: url),
        fullscreenDialog: true,
      ),
    );

    if (!mounted) return;
    setState(() => _isProcessing = false);
    try { await _controller?.start(); } catch (_) {}
  }

  bool _isValidUrl(String url) {
    try {
      final uri = Uri.parse(url);
      if (uri.scheme != 'http' && uri.scheme != 'https') return false;
      if (uri.host.isEmpty) return false;
      return true;
    } catch (_) {
      return false;
    }
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          msg,
          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
        ),
        backgroundColor: Colors.orange,
        duration: const Duration(seconds: 2),
      ),
    );
  }

  Future<void> _onTitleTap() async {
    final now = DateTime.now();
    _titleTaps.add(now);
    _titleTaps.removeWhere(
      (t) => now.difference(t) > const Duration(seconds: 3),
    );

    if (_titleTaps.length >= 3) {
      _titleTaps.clear();
      await _promptAdminAccess();
    }
  }

  Future<void> _promptAdminAccess() async {
    final pinController = TextEditingController();
    String? errorText;

    final ok = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('Masukkan PIN'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Masukkan PIN untuk melanjutkan:'),
              const SizedBox(height: 12),
              TextField(
                controller: pinController,
                autofocus: true,
                obscureText: true,
                keyboardType: TextInputType.number,
                maxLength: 8,
                decoration: InputDecoration(
                  border: const OutlineInputBorder(),
                  hintText: 'PIN (default: 2468)',
                  errorText: errorText,
                  errorStyle: const TextStyle(
                    color: Colors.red,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('Batal'),
            ),
            TextButton(
              onPressed: () async {
                await PinService.resetPin();
                if (!ctx.mounted) return;
                setDialogState(() => errorText = null);
                pinController.clear();
                _showSnack('PIN direset ke default. Ketik PIN default lalu tekan Lanjut.');
              },
              child: const Text('Reset PIN', style: TextStyle(color: Colors.orange)),
            ),
            FilledButton(
              onPressed: () async {
                FocusScope.of(ctx).unfocus();
                final valid = await PinService.verify(pinController.text);
                if (!ctx.mounted) return;
                if (valid) {
                  Navigator.of(ctx).pop(true);
                } else {
                  setDialogState(() => errorText = 'PIN SALAH!');
                }
              },
              child: const Text('Lanjut'),
            ),
          ],
        ),
      ),
    );

    if (!mounted || ok != true) return;

    try {
      setState(() => _isProcessing = true);
      try { await _controller?.stop(); } catch (_) {}

      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => const PinSettingsScreen()),
      );

      if (!mounted) return;
      try { await _controller?.start(); } catch (_) {}
      await Future.delayed(const Duration(seconds: 2));
      if (!mounted) return;
      setState(() => _isProcessing = false);
    } catch (e) {
      debugPrint('Admin access error: $e');
      if (mounted) setState(() => _isProcessing = false);
    }
  }

  void _showAbout() {
    showAboutDialog(
      context: context,
      applicationName: 'Exam Kayden',
      applicationVersion: '1.0.0',
      applicationIcon: const Icon(Icons.lock, size: 40, color: Color(0xFF1E3A8A)),
      applicationLegalese: '© 2026 Kayden',
      children: const [
        SizedBox(height: 12),
        Text('Aplikasi pengunci layar ujian.'),
        SizedBox(height: 8),
        Text('Dikembangkan oleh: Kayden',
            style: TextStyle(fontWeight: FontWeight.w600)),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        _buildMainScreen(),
        if (_overlayDetected) _buildOverlayWarning(),
      ],
    );
  }

  Widget _buildOverlayWarning() {
    return Positioned.fill(
      child: GestureDetector(
        onTap: () {},
        behavior: HitTestBehavior.opaque,
        child: Material(
          color: Colors.red.shade900.withValues(alpha: 0.97),
          child: const Center(
            child: Padding(
              padding: EdgeInsets.all(32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.warning_amber_rounded, color: Colors.white, size: 80),
                  SizedBox(height: 20),
                  Text(
                    'FLOATING APP TERDETEKSI',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  SizedBox(height: 16),
                  Text(
                    'Tutup semua aplikasi floating / mengambang di layar Anda.\n\n'
                    'Aplikasi ini tidak bisa digunakan selama ada aplikasi lain yang tampil di atas layar.',
                    style: TextStyle(color: Colors.white, fontSize: 16),
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMainScreen() {
    return Scaffold(
      appBar: AppBar(
        title: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: _onTitleTap,
          child: const Padding(
            padding: EdgeInsets.symmetric(horizontal: 20, vertical: 10),
            child: Text('Exam Kayden'),
          ),
        ),
        centerTitle: true,
        actions: [
          if (_cameraReady) ...[
            IconButton(
              tooltip: 'Flash',
              icon: const Icon(Icons.flash_on),
              onPressed: () => _controller?.toggleTorch(),
            ),
            IconButton(
              tooltip: 'Ganti kamera',
              icon: const Icon(Icons.cameraswitch),
              onPressed: () => _controller?.switchCamera(),
            ),
          ],
          IconButton(
            tooltip: 'Tentang',
            icon: const Icon(Icons.info_outline),
            onPressed: _showAbout,
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: _cameraReady && _controller != null
                ? Stack(
                    fit: StackFit.expand,
                    children: [
                      MobileScanner(
                        controller: _controller!,
                        onDetect: _onDetect,
                        errorBuilder: (_, _, _) => const _CameraError(),
                      ),
                      const _ScannerOverlay(),
                    ],
                  )
                : Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.camera_alt, size: 64, color: Colors.grey),
                        const SizedBox(height: 16),
                        const Text('Menunggu izin kamera...'),
                        const SizedBox(height: 12),
                        FilledButton(
                          onPressed: _initCamera,
                          child: const Text('Coba Lagi'),
                        ),
                      ],
                    ),
                  ),
          ),
          Container(
            padding: const EdgeInsets.all(16),
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            child: Column(
              children: [
                const Text(
                  'Arahkan kamera ke QR code ujian',
                  style: TextStyle(fontSize: 14),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _urlController,
                        decoration: const InputDecoration(
                          hintText: 'atau ketik URL ujian di sini',
                          border: OutlineInputBorder(),
                          isDense: true,
                          prefixIcon: Icon(Icons.link, size: 20),
                        ),
                        keyboardType: TextInputType.url,
                      ),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: () => _launchExam(_urlController.text),
                      child: const Text('Buka'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                const Text(
                  'Developed by Kayden',
                  style: TextStyle(fontSize: 11, color: Colors.black54),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ScannerOverlay extends StatelessWidget {
  const _ScannerOverlay();

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Center(
        child: Container(
          width: 260,
          height: 260,
          decoration: BoxDecoration(
            border: Border.all(color: Colors.white, width: 3),
            borderRadius: BorderRadius.circular(16),
          ),
        ),
      ),
    );
  }
}

class _CameraError extends StatelessWidget {
  const _CameraError();

  @override
  Widget build(BuildContext context) {
    return const ColoredBox(
      color: Colors.black,
      child: Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text(
            'Kamera tidak tersedia. Berikan izin kamera atau gunakan input URL manual di bawah.',
            style: TextStyle(color: Colors.white),
            textAlign: TextAlign.center,
          ),
        ),
      ),
    );
  }
}
