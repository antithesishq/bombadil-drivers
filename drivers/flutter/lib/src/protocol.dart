/// Version of Bombadil's external driver protocol this package speaks.
const protocolVersion = 0;

/// Messages the driver sends to Bombadil.
sealed class DriverMessage {
  const DriverMessage();

  Map<String, Object?> toJson();
}

class Hello extends DriverMessage {
  const Hello(this.name);

  final String name;

  @override
  Map<String, Object?> toJson() =>
      {'type': 'hello', 'protocol': protocolVersion, 'name': name};
}

class State extends DriverMessage {
  const State(this.state);

  /// Any JSON-encodable value describing the app.
  final Object? state;

  @override
  Map<String, Object?> toJson() => {'type': 'state', 'state': state};
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
