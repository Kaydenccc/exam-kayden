import 'package:flutter_test/flutter_test.dart';

import 'package:pengunci_ujian/main.dart';

void main() {
  testWidgets('App builds', (WidgetTester tester) async {
    await tester.pumpWidget(const PenguncUjianApp());
    expect(find.text('Scan QR Ujian'), findsWidgets);
  });
}
