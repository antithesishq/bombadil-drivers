import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:bombadil_flutter/bombadil_flutter.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeDriver implements BombadilDriver {
  final applied = <Object?>[];
  var stopped = false;
  var capture = false;
  State? initial;
  State? result;

  @override
  String get name => 'fake';

  @override
  Future<State> start() async => initial ?? const State({'applied': 0});

  @override
  Future<State> apply(Object? action) async {
    if (action == 'Crash') throw StateError('crashed');
    applied.add(action);
    return result ?? State({'applied': applied.length});
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
        '{"type":"apply_action","action":"Back"}',
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
        final message = State(
          state,
          screenshotPng: [137, 80, 1, 255],
          timestampUs: (sent[2] as Map)['timestamp_us'] as int,
        );
        expect(jsonDecode(message.encode()), sent[2]);
        expect(message.toJson(), sent[2]);
      }
    },
  );

  test('reports a state for the start and for every applied action', () async {
    final driver = FakeDriver();
    final (sent, error) = await session(driver, [
      '{"type":"apply_action","action":{"Tap":{"id":"ok"}}}',
      '{"type":"apply_action","action":"Back"}',
      '{"type":"terminate"}',
      '{"type":"apply_action","action":"AfterTerminate"}',
    ]);
    final timestamps = sent
        .skip(1)
        .map((s) => (s as Map)['timestamp_us'] as int)
        .toList();
    expect(timestamps, orderedEquals([...timestamps]..sort()));
    expect(timestamps.first, greaterThan(1700000000000000));
    for (final state in sent.skip(1)) {
      (state as Map).remove('timestamp_us');
    }
    expect(sent, [
      {'type': 'hello', 'protocol': 0, 'name': 'fake'},
      {
        'type': 'state',
        'settled': true,
        'state': {'applied': 0},
      },
      {
        'type': 'state',
        'settled': true,
        'state': {'applied': 1},
      },
      {
        'type': 'state',
        'settled': true,
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

  test('rejects the obsolete apply request without applying it', () async {
    final driver = FakeDriver();
    final (sent, error) = await session(driver, [
      '{"type":"apply","action":"Back"}',
    ]);
    expect(error, isFormatException);
    expect(sent, hasLength(3));
    expect((sent.last as Map)['type'], 'error');
    expect((sent.last as Map)['message'], contains('invalid message'));
    expect(driver.applied, isEmpty);
    expect(driver.stopped, isTrue);
  });

  test('preserves arbitrary JSON and metadata on both encoding paths', () {
    for (final app in [
      null,
      true,
      42,
      'text',
      [1, null],
      {'settled': 'app data'},
    ]) {
      for (final payload in [app, EncodedJson(jsonEncode(app))]) {
        final message = State(
          payload,
          settled: false,
          notApplied: 'no node "missing"\ntry again',
          timestampUs: 1700000000000001,
          screenshotPng: [137, 80, 1, 255],
        );
        final expected = {
          'type': 'state',
          'state': app,
          'settled': false,
          'not_applied': 'no node "missing"\ntry again',
          'timestamp_us': 1700000000000001,
          'screenshot_png': 'iVAB/w==',
        };
        expect(message.toJson(), expected);
        expect(jsonDecode(message.encode()), expected);
      }
    }
  });

  test('sends metadata and clamps backward capture times', () async {
    final driver = FakeDriver()
      ..initial = const State(
        null,
        settled: false,
        timestampUs: 1700000000000002,
      )
      ..result = const State(
        EncodedJson('["unchanged"]'),
        settled: false,
        notApplied: 'no node missing',
        timestampUs: 1700000000000001,
      );
    final (sent, error) = await session(driver, [
      '{"type":"apply_action","action":"Back"}',
    ]);
    expect(error, isNull);
    expect(sent[1], {
      'type': 'state',
      'state': null,
      'settled': false,
      'timestamp_us': 1700000000000002,
    });
    expect(sent[2], {
      'type': 'state',
      'state': ['unchanged'],
      'settled': false,
      'not_applied': 'no node missing',
      'timestamp_us': 1700000000000002,
    });
  });

  test('rejects not_applied on the initial state and stops', () async {
    final driver = FakeDriver()
      ..initial = const State(null, notApplied: 'invalid');
    final (sent, error) = await session(driver, []);
    expect(error, isStateError);
    expect(sent, [
      {'type': 'hello', 'protocol': 0, 'name': 'fake'},
      {
        'type': 'error',
        'message': 'Bad state: initial state must not contain not_applied',
      },
    ]);
    expect(driver.stopped, isTrue);
  });

  test('reports driver errors to Bombadil and stops the app', () async {
    final driver = FakeDriver();
    final (sent, error) = await session(driver, [
      '{"type":"apply_action","action":"Crash"}',
    ]);
    expect(error, isStateError);
    expect(sent.last, {'type': 'error', 'message': 'Bad state: crashed'});
    expect(driver.stopped, isTrue);
  });
}
