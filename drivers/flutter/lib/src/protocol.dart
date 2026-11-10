import 'dart:convert';

/// Version of Bombadil's external driver protocol this package speaks.
const protocolVersion = 0;

/// Messages the driver sends to Bombadil.
sealed class DriverMessage {
  const DriverMessage();

  Map<String, Object?> toJson();

  /// The message as one line of JSON, without the newline.
  String encode() => jsonEncode(toJson());
}

class Hello extends DriverMessage {
  const Hello(this.name, {this.stallTimeout});

  final String name;

  /// How long Bombadil should wait for a state before it considers the
  /// driver stalled, if not its default.
  final Duration? stallTimeout;

  @override
  Map<String, Object?> toJson() => {
    'type': 'hello',
    'protocol': protocolVersion,
    'name': name,
    if (stallTimeout case final timeout?)
      'stall_timeout_ms': timeout.inMilliseconds,
  };
}

class State extends DriverMessage {
  const State(
    this.state, {
    this.settled = true,
    this.notApplied,
    this.timestampUs,
    this.screenshotPng,
  });

  /// Any JSON-encodable value describing the app, or [EncodedJson].
  final Object? state;

  /// Whether the app went quiet before this state was captured.
  final bool settled;

  /// Why the requested action did not apply; forbidden on the initial state.
  final String? notApplied;

  /// Capture time in host epoch microseconds, not the app's virtual clock.
  /// [runDriver] supplies this if omitted and clamps backward clock changes
  /// to the previous state's time.
  final int? timestampUs;
  final List<int>? screenshotPng;

  Map<String, Object?> get _metadata => {
    'type': 'state',
    'settled': settled,
    if (notApplied case final reason?) 'not_applied': reason,
    if (timestampUs case final timestamp?) 'timestamp_us': timestamp,
    if (screenshotPng case final bytes?) 'screenshot_png': base64Encode(bytes),
  };

  @override
  Map<String, Object?> toJson() => {
    ..._metadata,
    'state': switch (state) {
      EncodedJson(:final json) => jsonDecode(json),
      final state => state,
    },
  };

  @override
  String encode() {
    if (state case EncodedJson(:final json)) {
      final metadata = jsonEncode(_metadata);
      return '${metadata.substring(0, metadata.length - 1)},"state":$json}';
    }
    return super.encode();
  }
}

/// A state the driver has already encoded as JSON, so that it can time or
/// reuse the encoding.
class EncodedJson {
  const EncodedJson(this.json);

  final String json;
}

class DriverError extends DriverMessage {
  const DriverError(this.message);

  final String message;

  @override
  Map<String, Object?> toJson() => {'type': 'error', 'message': message};
}

/// Messages Bombadil sends to the driver.
sealed class BombadilMessage {
  const BombadilMessage();

  factory BombadilMessage.fromJson(Object? json) {
    if (json is Map<String, Object?>) {
      switch (json['type']) {
        case 'apply':
          return Apply(json['action']);
        case 'terminate':
          return const Terminate();
      }
    }
    throw FormatException('invalid message from Bombadil: $json');
  }
}

class Apply extends BombadilMessage {
  const Apply(this.action);

  /// A concrete action, in the shape the specification chose.
  final Object? action;
}

class Terminate extends BombadilMessage {
  const Terminate();
}
