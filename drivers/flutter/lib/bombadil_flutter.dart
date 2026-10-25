/// Write Bombadil external drivers in Dart.
///
/// Implement [BombadilDriver] and pass it to [runDriver], which speaks
/// Bombadil's external driver protocol (version [protocolVersion]) on
/// stdin and stdout. Log to stderr: stdout carries the protocol.
library;

export 'src/driver.dart';
export 'src/protocol.dart';
