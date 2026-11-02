import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:bombadil_flutter/bombadil_flutter.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeDriver implements BombadilDriver {
  final applied = <Object?>[];
  var stopped = false;
  var capture = false;

  @override
  String get name => 'fake';

  @override
  Future<Object?> start() async => {'applied': 0};

  @override
  Future<Object?> apply(Object? action) async {
    if (action == 'Crash') throw StateError('crashed');
    applied.add(action);
    return {'applied': applied.length};
  }

  @override
  Future<List<int>?> screenshotPng() async =>
      capture ? [137, 80, applied.length, 255] : null;

  @override
  Future<void> stop() async => stopped = true;
}

/// Runs [driver] on [input] lines and returns the messages it sent, and the
/// error it failed with.
Future<(List<Object?>, Object?)> session(
  BombadilDriver driver,
  List<String> input,
) async {
  final output = StreamController<List<int>>();
  final sent = output.stream
      .transform(utf8.decoder)
      .transform(const LineSplitter())
      .map(jsonDecode)
      .toList();
  Object? error;
  try {
    await runDriver(
      driver,
      input: Stream.value(utf8.encode(input.map((l) => '$l\n').join())),
      output: IOSink(output),
    );
  } catch (e) {
    error = e;
  } finally {
    await output.close();
  }
  return (await sent, error);
}

void main() {
  test(
    'attaches screenshots to their states outside the application JSON',
    () async {
      final driver = FakeDriver()..capture = true;
      final (sent, error) = await session(driver, [
        '{"type":"apply","action":"Back"}',
        '{"type":"terminate"}',
      ]);
      expect(error, isNull);
      expect((sent[1] as Map)['screenshot_png'], 'iVAA/w==');
      expect((sent[2] as Map)['screenshot_png'], 'iVAB/w==');
      expect((sent[2] as Map)['state'], {'applied': 1});
      for (final state in [
        const EncodedJson('{"applied":1}'),
        {'applied': 1},
      ]) {
        final message = State(state, screenshotPng: [137, 80, 1, 255]);
        expect(jsonDecode(message.encode()), sent[2]);
        expect(message.toJson(), sent[2]);
      }
    },
  );

  test('reports a state for the start and for every applied action', () async {
    final driver = FakeDriver();
    final (sent, error) = await session(driver, [
      '{"type":"apply","action":{"Tap":{"id":"ok"}}}',
      '{"type":"apply","action":"Back"}',
      '{"type":"terminate"}',
      '{"type":"apply","action":"AfterTerminate"}',
    ]);
    expect(sent, [
      {'type': 'hello', 'protocol': 0, 'name': 'fake'},
      {
        'type': 'state',
        'state': {'applied': 0},
      },
      {
        'type': 'state',
        'state': {'applied': 1},
      },
      {
        'type': 'state',
        'state': {'applied': 2},
      },
    ]);
    expect(driver.applied, [
      {
        'Tap': {'id': 'ok'},
      },
      'Back',
    ]);
    expect(error, isNull);
    expect(driver.stopped, isTrue);
  });

  test('reports driver errors to Bombadil and stops the app', () async {
    final driver = FakeDriver();
    final (sent, error) = await session(driver, [
      '{"type":"apply","action":"Crash"}',
    ]);
    expect(error, isStateError);
    expect(sent.last, {'type': 'error', 'message': 'Bad state: crashed'});
    expect(driver.stopped, isTrue);
  });
}
