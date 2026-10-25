// A driver for a pretend counter app, with a bug: nothing caps the
// count. Run it under Bombadil with:
//
//   bombadil external test --specification example/counter.spec.ts \
//     -- dart run example/counter_driver.dart
import 'package:bombadil_flutter/bombadil_flutter.dart';

class CounterDriver implements BombadilDriver {
  var _count = 0;

  @override
  String get name => 'counter (dart)';

  Map<String, Object?> get _state => {'count': _count};

  @override
  Future<Object?> start() async => _state;

  @override
  Future<Object?> apply(Object? action) async {
    switch (action) {
      case {'Increment': {'by': final int by}}:
        _count += by;
      case 'Reset':
        _count = 0;
      default:
        throw ArgumentError('unknown action: $action');
    }
    return _state;
  }

  @override
  Future<void> stop() async {}
}

Future<void> main() => runDriver(CounterDriver());
