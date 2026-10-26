/// Test Flutter apps with Bombadil, from inside the app.
///
/// An app's Bombadil entrypoint calls [runBombadil] with the app's `main`.
/// The driver then speaks Bombadil's external driver protocol (version
/// [protocolVersion]): states are the app's semantics tree, actions are
/// semantics actions on its nodes. [BombadilDriver] and [runDriver] are the
/// protocol layer, for other drivers.
library;

export 'src/driver.dart';
export 'src/flutter/actions.dart' show DriverAction, ActionError;
export 'src/flutter/binding.dart' show AppMonitor, BombadilBinding, FrameClock;
export 'src/flutter/run.dart';
export 'src/flutter/semantics.dart' show SemanticsSnapshot;
export 'src/flutter/settle.dart';
export 'src/protocol.dart';
