import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import '../driver.dart';
import 'app_driver.dart';
import 'binding.dart';

/// Runs [app] (typically the app's `main`) under Bombadil, as the driver
/// for one test run, and exits when Bombadil terminates it.
///
/// Call it from the app's Bombadil entrypoint, instead of the app's
/// `main`:
///
/// ```dart
/// import 'package:bombadil_flutter/bombadil_flutter.dart';
/// import 'package:my_app/main.dart' as app;
///
/// void main() => runBombadil(app.main, name: 'my_app');
/// ```
///
/// The app runs with [BombadilBinding] in a zone that the driver watches.
/// The driver reads Bombadil's messages on stdin and writes its own to
/// file descriptor `BOMBADIL_FLUTTER_PROTOCOL_FD` if that is set, else to
/// stdout. Font families in the subdirectories of `fontsDir` (default:
/// `BOMBADIL_FLUTTER_FONTS`) are loaded before the app starts, one family
/// per subdirectory. The view is [size] logical pixels (default:
/// `BOMBADIL_FLUTTER_SIZE`, else `1024x768`) at [devicePixelRatio].
/// `BOMBADIL_FLUTTER_TIMING=1` logs where each step's time goes.
Future<Never> runBombadil(
  FutureOr<void> Function() app, {
  String name = 'bombadil_flutter',
  Duration settleCap = const Duration(seconds: 5),
  Duration startupCap = const Duration(seconds: 45),
  Duration stallTimeout = const Duration(seconds: 60),
  String? fontsDir,
  Size? size,
  double devicePixelRatio = 1,
  bool rasterize = false,
}) async {
  final env = Platform.environment;
  final viewSize =
      size ?? parseSize(env['BOMBADIL_FLUTTER_SIZE'] ?? '1024x768');
  final monitor = AppMonitor();
  final driverZone = Zone.current;
  final appZone = driverZone.fork(specification: monitor.zoneSpecification());
  final binding = appZone.run(() {
    final binding = BombadilBinding.ensureInitialized(
      monitor,
      driverZone,
      size: viewSize,
      devicePixelRatio: devicePixelRatio,
      rasterize: rasterize || env['BOMBADIL_FLUTTER_RASTERIZE'] == '1',
    );
    FlutterError.onError = (details) {
      monitor.reportError(
        '${details.library ?? 'Flutter'}: ${details.exceptionAsString()}',
      );
      FlutterError.presentError(details);
    };
    binding.platformDispatcher.onError = (error, stack) {
      monitor.reportError('Unhandled exception: $error');
      stderr.writeln('Unhandled exception: $error\n$stack');
      return true;
    };
    // A blinking cursor would keep the app busy.
    EditableText.debugDeterministicCursor = true;
    binding.resume();
    binding.ensureSemantics();
    return binding;
  });

  final fonts = fontsDir ?? env['BOMBADIL_FLUTTER_FONTS'];
  if (fonts != null) await appZone.run(() => loadFontFamilies(fonts));
  appZone.run(() async => await app());

  final fd = env['BOMBADIL_FLUTTER_PROTOCOL_FD'];
  final output = fd == null
      ? stdout
      : File('/proc/self/fd/$fd').openWrite(mode: FileMode.writeOnly);
  var code = 0;
  try {
    await runDriver(
      FlutterAppDriver(
        binding,
        name: name,
        settleCap: settleCap,
        startupCap: startupCap,
        timing: env['BOMBADIL_FLUTTER_TIMING'] == '1',
      ),
      output: output,
      stallTimeout: stallTimeout,
    );
  } on Object catch (error, stack) {
    stderr.writeln('bombadil_flutter: $error\n$stack');
    code = 1;
  }
  await output.flush();
  exit(code);
}

/// Loads a font family from each subdirectory of [dir], named after it,
/// from the `.ttf`, `.otf` and `.ttc` files in it.
///
/// Apps that don't bundle their fonts use the system's, and the engine
/// only finds those in `/usr/share/fonts`; without fonts, text has no
/// width, and layouts collapse.
Future<void> loadFontFamilies(String dir) async {
  for (final family in Directory(dir).listSync()) {
    if (family is! Directory) continue;
    final loader = FontLoader(
      family.uri.pathSegments.lastWhere((s) => s.isNotEmpty),
    );
    for (final file in family.listSync()) {
      if (file is File &&
          RegExp(
            r'\.(ttf|otf|ttc)$',
            caseSensitive: false,
          ).hasMatch(file.path)) {
        loader.addFont(file.readAsBytes().then(ByteData.sublistView));
      }
    }
    await loader.load();
  }
}

/// Parses a size such as `1024x768`.
Size parseSize(String size) {
  final match = RegExp(r'^(\d+)x(\d+)$').firstMatch(size);
  if (match == null) throw FormatException('bad size: $size');
  return Size(double.parse(match[1]!), double.parse(match[2]!));
}
