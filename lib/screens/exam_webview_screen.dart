import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../services/kiosk_service.dart';
import '../services/pin_service.dart';

class ExamWebViewScreen extends StatefulWidget {
  final String url;
  const ExamWebViewScreen({super.key, required this.url});

  @override
  State<ExamWebViewScreen> createState() => _ExamWebViewScreenState();
}

class _ExamWebViewScreenState extends State<ExamWebViewScreen>
    with WidgetsBindingObserver {
  InAppWebViewController? _webController;
  bool _loading = true;
  double _progress = 0;
  Timer? _clipboardCleaner;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    KioskService.enterKiosk();
    _startClipboardCleaner();
  }

  @override
  void dispose() {
    _clipboardCleaner?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    KioskService.exitKiosk();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      KioskService.enterKiosk();
      _clipboardCleaner?.cancel();
      _startClipboardCleaner();
    } else if (state == AppLifecycleState.paused) {
      _clipboardCleaner?.cancel();
    }
  }

  void _startClipboardCleaner() {
    _clearClipboard();
    _clipboardCleaner = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _clearClipboard(),
    );
  }

  Future<void> _clearClipboard() async {
    try {
      await Clipboard.setData(const ClipboardData(text: ''));
    } catch (_) {}
  }

  Future<bool> _confirmExit() async {
    final pinController = TextEditingController();
    String? errorText;
    bool obscure = true;

    final ok = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('Keluar dari ujian?'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Masukkan PIN untuk keluar:'),
              const SizedBox(height: 12),
              TextField(
                controller: pinController,
                autofocus: true,
                obscureText: obscure,
                keyboardType: TextInputType.number,
                maxLength: 8,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(
                  border: const OutlineInputBorder(),
                  hintText: 'PIN',
                  errorText: errorText,
                  errorStyle: const TextStyle(
                    color: Colors.red,
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                  suffixIcon: IconButton(
                    icon: Icon(obscure ? Icons.visibility_off : Icons.visibility),
                    onPressed: () => setDialogState(() => obscure = !obscure),
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
            FilledButton(
              onPressed: () async {
                FocusScope.of(ctx).unfocus();
                final input = pinController.text.trim();
                final storedPin = await PinService.getPin();
                if (!ctx.mounted) return;
                if (input == storedPin) {
                  Navigator.of(ctx).pop(true);
                } else {
                  setDialogState(() =>
                      errorText = 'PIN salah! (Anda ketik: $input)');
                }
              },
              child: const Text('Keluar'),
            ),
          ],
        ),
      ),
    );

    if (ok == true) {
      _clipboardCleaner?.cancel();
      await KioskService.exitKiosk();
    }

    return ok == true;
  }

  static const String _antiCopyPasteJs = r"""
    (function() {
      const block = (e) => { e.preventDefault(); e.stopPropagation(); return false; };
      document.addEventListener('copy', block, true);
      document.addEventListener('cut', block, true);
      document.addEventListener('paste', block, true);
      document.addEventListener('contextmenu', block, true);
      document.addEventListener('dragstart', block, true);
      document.addEventListener('drop', block, true);
      document.addEventListener('selectstart', block, true);

      document.addEventListener('keydown', (e) => {
        const k = (e.key || '').toLowerCase();
        if ((e.ctrlKey || e.metaKey) && ['c','v','x','a','p','s','u'].includes(k)) {
          e.preventDefault(); e.stopPropagation();
        }
        if (k === 'printscreen' || (e.ctrlKey && k === 'insert')) {
          e.preventDefault(); e.stopPropagation();
        }
      }, true);

      const style = document.createElement('style');
      style.innerHTML = `
        * { -webkit-user-select: none !important; user-select: none !important; }
        input, textarea, [contenteditable="true"] {
          -webkit-user-select: text !important; user-select: text !important;
        }
      `;
      document.head.appendChild(style);
    })();
  """;

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final allowed = await _confirmExit();
        if (!mounted) return;
        // ignore: use_build_context_synchronously
        if (allowed) Navigator.of(context).pop();
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: SafeArea(
          child: Column(
            children: [
              _buildTopBar(),
              if (_loading)
                LinearProgressIndicator(
                  value: _progress == 0 ? null : _progress,
                ),
              Expanded(
                child: InAppWebView(
                  initialUrlRequest: URLRequest(url: WebUri(widget.url)),
                  initialSettings: InAppWebViewSettings(
                    javaScriptEnabled: true,
                    disableContextMenu: true,
                    disableLongPressContextMenuOnLinks: true,
                    incognito: false,
                    cacheEnabled: true,
                    supportZoom: true,
                    builtInZoomControls: true,
                    displayZoomControls: false,
                    useHybridComposition: true,
                    transparentBackground: false,
                    allowsInlineMediaPlayback: true,
                    mediaPlaybackRequiresUserGesture: false,
                    useOnDownloadStart: true,
                  ),
                  onWebViewCreated: (c) => _webController = c,
                  onLoadStart: (_, _) {
                    if (mounted) setState(() => _loading = true);
                  },
                  onProgressChanged: (_, p) {
                    if (mounted) setState(() => _progress = p / 100);
                  },
                  onLoadStop: (c, _) async {
                    if (mounted) setState(() => _loading = false);
                    await c.evaluateJavascript(source: _antiCopyPasteJs);
                  },
                  onReceivedError: (controller, request, error) {
                    if (request.isForMainFrame == true && mounted) {
                      controller.loadData(
                        data: '''
                          <html><body style="display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif;background:#111;color:#fff;text-align:center;padding:20px;">
                            <div>
                              <h2>Halaman tidak bisa dimuat</h2>
                              <p>${error.description}</p>
                              <p style="color:#aaa;">Periksa koneksi internet lalu tekan tombol muat ulang di atas.</p>
                            </div>
                          </body></html>
                        ''',
                      );
                    }
                  },
                  onDownloadStartRequest: (_, _) {},
                  onPermissionRequest: (_, req) async {
                    return PermissionResponse(
                      resources: req.resources,
                      action: PermissionResponseAction.DENY,
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTopBar() {
    return Container(
      height: 44,
      color: const Color(0xFF1E3A8A),
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: [
          const Icon(Icons.lock, color: Colors.white, size: 18),
          const SizedBox(width: 8),
          const Expanded(
            child: Text(
              'Mode Ujian Terkunci',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
            ),
          ),
          IconButton(
            tooltip: 'Muat ulang',
            icon: const Icon(Icons.refresh, color: Colors.white, size: 20),
            onPressed: () => _webController?.reload(),
          ),
          IconButton(
            tooltip: 'Keluar',
            icon: const Icon(Icons.close, color: Colors.white, size: 20),
            onPressed: () async {
              final ok = await _confirmExit();
              if (ok && mounted) {
                HapticFeedback.lightImpact();
                Navigator.of(context).pop();
              }
            },
          ),
        ],
      ),
    );
  }
}
