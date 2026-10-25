import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'protocol.dart';

/// Drives one app session for Bombadil.
///
/// States and actions are JSON values (maps, lists, strings, numbers,
/// booleans and null) whose shape the driver and the specification agree
/// on.
abstract interface class BombadilDriver {
  /// Name reported to Bombadil in the handshake.
  String get name;

  /// Launches the app and returns its first settled state.
  Future<Object?> start();

  /// Performs [action] and returns the state the app settles into.
  Future<Object?> apply(Object? action);

  /// Shuts the app down.
  Future<void> stop();
}

/// Runs [driver] for one Bombadil session over [input] and [output]
/// (stdin and stdout by default), until Bombadil terminates it or closes
/// [input].
///
/// Errors from the driver are reported to Bombadil and rethrown.
Future<void> runDriver(
  BombadilDriver driver, {
  Stream<List<int>>? input,
  IOSink? output,
}) async {
  final sink = output ?? stdout;
  void send(DriverMessage message) =>
      sink.writeln(jsonEncode(message.toJson()));

  final lines = (input ?? stdin)
      .transform(utf8.decoder)
      .transform(const LineSplitter())
      .where((line) => line.trim().isNotEmpty);

  send(Hello(driver.name));
  try {
    send(State(await driver.start()));
    await for (final line in lines) {
      switch (BombadilMessage.fromJson(jsonDecode(line))) {
        case Apply(:final action):
          send(State(await driver.apply(action)));
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
