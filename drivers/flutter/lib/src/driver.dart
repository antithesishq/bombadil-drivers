import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'protocol.dart';

/// Drives one app session for Bombadil.
///
/// [State.state] and actions are arbitrary JSON values whose shape the
/// driver and the specification agree on. Protocol metadata lives on [State],
/// outside the application JSON.
abstract interface class BombadilDriver {
  /// Name reported to Bombadil in the handshake.
  String get name;

  /// Launches the app and returns its first state, without [State.notApplied].
  Future<State> start();

  /// Performs [action] and returns its resulting state, including whether
  /// it settled and, if it did not apply, the actual reason.
  Future<State> apply(Object? action);

  Future<List<int>?> screenshotPng();

  /// Shuts the app down.
  Future<void> stop();
}

/// Runs [driver] for one Bombadil session over [input] and [output]
/// (stdin and stdout by default), until Bombadil terminates it or closes
/// [input]. [stallTimeout] goes to Bombadil in the handshake.
///
/// Errors from the driver are reported to Bombadil and rethrown.
Future<void> runDriver(
  BombadilDriver driver, {
  Stream<List<int>>? input,
  IOSink? output,
  Duration? stallTimeout,
}) async {
  final sink = output ?? stdout;
  void send(DriverMessage message) => sink.writeln(message.encode());

  int? previousTimestamp;
  Future<void> sendState(State state) async {
    final captured = state.timestampUs ?? DateTime.now().microsecondsSinceEpoch;
    final timestamp = previousTimestamp != null && captured < previousTimestamp!
        ? previousTimestamp!
        : captured;
    previousTimestamp = timestamp;
    send(
      State(
        state.state,
        settled: state.settled,
        notApplied: state.notApplied,
        timestampUs: timestamp,
        screenshotPng: state.screenshotPng ?? await driver.screenshotPng(),
      ),
    );
  }

  final lines = (input ?? stdin)
      .transform(utf8.decoder)
      .transform(const LineSplitter())
      .where((line) => line.trim().isNotEmpty);

  send(Hello(driver.name, stallTimeout: stallTimeout));
  try {
    final initial = await driver.start();
    if (initial.notApplied != null) {
      throw StateError('initial state must not contain not_applied');
    }
    await sendState(initial);
    await for (final line in lines) {
      switch (BombadilMessage.fromJson(jsonDecode(line))) {
        case ApplyAction(:final action):
          final state = await driver.apply(action);
          await sendState(state);
        case Terminate():
          return;
      }
    }
  } catch (error) {
    send(DriverError('$error'));
    rethrow;
  } finally {
    await driver.stop();
    await sink.flush();
  }
}
