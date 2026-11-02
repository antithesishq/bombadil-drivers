import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/semantics.dart';

import '../driver.dart';
import '../protocol.dart';
import 'actions.dart';
import 'binding.dart';
import 'semantics.dart';
import 'settle.dart' as quiescence;

/// The driver for the app that [binding] runs: states are its semantics
/// tree, actions are semantics actions on its nodes.
class FlutterAppDriver implements BombadilDriver, ActionTarget {
  FlutterAppDriver(
    this.binding, {
    required this.name,
    this.settleCap = const Duration(seconds: 5),
    this.startupCap = const Duration(seconds: 45),
    this.timing = false,
  });

  final BombadilBinding binding;

  @override
  final String name;

  /// How long to wait for the app to settle after an action before
  /// reporting a state with `settled: false`.
  final Duration settleCap;

  /// How long to wait for the app's first semantics tree.
  final Duration startupCap;

  /// Whether to log where each step's time goes to stderr.
  final bool timing;

  @override
  SemanticsSnapshot snapshot = SemanticsSnapshot.empty();

  ui.FlutterView get _view => binding.platformDispatcher.implicitView!;

  @override
  ui.Size get screen => _view.physicalSize / _view.devicePixelRatio;

  SemanticsNode? get _root {
    for (final view in binding.renderViews) {
      if (view.flutterView.viewId == _view.viewId) {
        return view.owner?.semanticsOwner?.rootSemanticsNode;
      }
    }
    return null;
  }

  @override
  SemanticsSnapshot refresh() => snapshot = SemanticsSnapshot.capture(
    _root,
    screen: screen,
    devicePixelRatio: _view.devicePixelRatio,
  );

  /// Wall time spent waiting this step, by what the app was busy with.
  final _waits = <String, Duration>{};
  final _waitClock = Stopwatch()..start();

  Future<bool> _settle(Duration cap) async {
    String? reason;
    var since = _waitClock.elapsed;
    void account(String? next) {
      final now = _waitClock.elapsed;
      if (reason case final r?) {
        _waits[r] = (_waits[r] ?? Duration.zero) + (now - since);
      }
      reason = next;
      since = now;
    }

    final settled = await quiescence.settle(
      binding,
      cap: cap,
      yieldToEventLoop: () => binding.driverZone.run(() => Future<void>(() {})),
      onWait: timing ? account : null,
    );
    account(null);
    return settled;
  }

  @override
  Future<void> settle() => _settle(settleCap);

  @override
  Future<void> tapAt(Offset position) => binding.tapAt(position);

  @override
  Future<void> back() => binding.back();

  @override
  Future<List<int>?> screenshotPng() async {
    final view = binding.renderViews.firstWhere(
      (view) => view.flutterView.viewId == _view.viewId,
    );
    // ignore: invalid_use_of_protected_member
    final scene = view.layer!.buildScene(ui.SceneBuilder());
    try {
      final image = await scene.toImage(
        _view.physicalSize.width.ceil(),
        _view.physicalSize.height.ceil(),
      );
      try {
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        if (bytes == null) throw StateError('PNG screenshot encoding failed');
        return bytes.buffer.asUint8List(
          bytes.offsetInBytes,
          bytes.lengthInBytes,
        );
      } finally {
        image.dispose();
      }
    } finally {
      scene.dispose();
    }
  }

  @override
  Future<Object?> start() async {
    binding.clock.startStep();
    final elapsed = Stopwatch()..start();
    while (true) {
      final settled = await _settle(startupCap - elapsed.elapsed);
      if (_root != null) return _state(settled, null);
      final left = startupCap - elapsed.elapsed;
      if (left <= Duration.zero) {
        throw StateError(
          'the app reported no semantics tree within '
          '${startupCap.inSeconds}s of starting',
        );
      }
      // Idle without a tree: the app waits for something before it shows
      // anything.
      await binding.changed.timeout(left, onTimeout: () {});
    }
  }

  @override
  Future<Object?> apply(Object? json) async {
    final action = DriverAction.parse(json);
    final step = Stopwatch()..start();
    binding.clock.startStep();
    _waits.clear();
    String? error;
    try {
      await binding.appZone.run(() => perform(action, this));
    } on ActionError catch (e) {
      error = e.message;
    } on Object catch (e, stack) {
      // The app threw while handling the action: an app error, which the
      // state reports.
      binding.monitor.reportError('Unhandled exception: $e');
      stderr.writeln('Unhandled exception: $e\n$stack');
    }
    final dispatched = step.elapsed;
    final settled = await _settle(settleCap);
    final settledAt = step.elapsed;
    final frames = binding.clock.frames;
    final state = _state(settled, {
      'action': json,
      'ok': error == null,
      'error': ?error,
    });
    final captured = step.elapsed;
    final encoded = EncodedJson(jsonEncode(state));
    if (timing) {
      final end = step.elapsed;
      String ms(Duration d) => (d.inMicroseconds / 1000).toStringAsFixed(1);
      stderr.writeln(
        'bombadil_flutter timing: action=${ms(dispatched)}ms '
        'settle=${ms(settledAt - dispatched)}ms frames=$frames '
        'capture=${ms(captured - settledAt)}ms '
        'encode=${ms(end - captured)}ms total=${ms(end)}ms '
        'waits=${_waits.entries.map((e) => '${e.key}:${ms(e.value)}').join(',')} '
        'bytes=${encoded.json.length} settled=$settled',
      );
    }
    return encoded;
  }

  Map<String, Object?> _state(bool settled, Map<String, Object?>? lastAction) {
    final tree = refresh().tree;
    final platform = binding.platform;
    final exitRequested = platform.exitRequested;
    platform.exitRequested = false;
    return {
      'route': platform.route,
      'size': [screen.width, screen.height],
      'tree': tree,
      'errors': binding.monitor.takeErrors(),
      'exitRequested': exitRequested,
      'settled': settled,
      'lastAction': ?lastAction,
    };
  }

  @override
  Future<void> stop() async {}
}
