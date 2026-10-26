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
  const State(this.state);

  /// Any JSON-encodable value describing the app, or [EncodedJson].
  final Object? state;

  @override
  Map<String, Object?> toJson() => {
    'type': 'state',
    'state': switch (state) {
      EncodedJson(:final json) => jsonDecode(json),
      final state => state,
    },
  };

  @override
  String encode() => switch (state) {
    EncodedJson(:final json) => '{"type":"state","state":$json}',
    _ => super.encode(),
  };
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
