import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart' show TestPlatformDispatcher;

import 'platform.dart';
import 'settle.dart';

/// Frame timestamps for frames the driver runs as soon as the app asks for
/// them, instead of at the display's pace.
///
/// Each frame is [interval] after the previous one in frame time, so
/// animations, which follow frame time, finish in a few milliseconds of
/// wall time. Frame time never falls behind wall time. After [fastFrames]
/// frames since [startStep] (two seconds of animation by default), frames
/// are paced in wall time: an app that animates while it waits for I/O,
/// such as a spinner, sees its I/O finish at a realistic point of the
/// animation.
class FrameClock {
  FrameClock({
    this.interval = const Duration(microseconds: 16667),
    this.fastFrames = 120,
  });

  final Duration interval;
  final int fastFrames;

  final _wall = Stopwatch()..start();
  Duration _now = Duration.zero;
  Duration? _lastFrameWall;

  /// Frames since [startStep].
  int frames = 0;

  /// The timestamp of the last frame.
  Duration get now => _now;

  /// Starts a step (an action): the next [fastFrames] frames run at once.
  void startStep() => frames = 0;

  /// How long to wait before running the next frame.
  Duration get delay {
    final last = _lastFrameWall;
    if (frames < fastFrames || last == null) return Duration.zero;
    final due = last + interval - _wall.elapsed;
    return due > Duration.zero ? due : Duration.zero;
  }

  /// The next frame's timestamp.
  Duration next() {
    frames++;
    final wall = _wall.elapsed;
    _lastFrameWall = wall;
    _now = Duration(
      microseconds: math.max(
        (_now + interval).inMicroseconds,
        wall.inMicroseconds,
      ),
    );
    return _now;
  }
}

/// What the driver watches of the app's zone: the errors it reports, and
/// the timers it queued to run as soon as possible.
class AppMonitor {
  /// Errors since the driver last took them, up to [maxErrors].
  final errors = <String>[];
  static const maxErrors = 20;

  /// Zero-delay timers (`Timer.run`, `Future(...)`) that haven't run yet.
  /// They are work the app has already queued, so settling waits for
  /// them. Timers with a delay are not: they are the app's own waits.
  int timers = 0;

  Completer<void>? _change;

  Future<void> get changed => (_change ??= Completer<void>()).future;

  void notify() {
    final change = _change;
    _change = null;
    change?.complete();
  }

  void reportError(String error) {
    if (errors.length < maxErrors) errors.add(error);
  }

  List<String> takeErrors() {
    final taken = List.of(errors);
    errors.clear();
    return taken;
  }

  /// A zone for the app: it counts zero-delay timers, sends prints to
  /// stderr (stdout may carry the protocol), and reports uncaught errors.
  ZoneSpecification zoneSpecification() => ZoneSpecification(
    createTimer: (self, parent, zone, duration, callback) {
      if (duration > Duration.zero) {
        return parent.createTimer(zone, duration, callback);
      }
      timers++;
      var done = false;
      void finish() {
        if (done) return;
        done = true;
        timers--;
        notify();
      }

      final timer = parent.createTimer(zone, duration, () {
        try {
          callback();
        } finally {
          finish();
        }
      });
      return _TrackedTimer(timer, finish);
    },
    print: (self, parent, zone, line) => stderr.writeln(line),
    handleUncaughtError: (self, parent, zone, error, stack) {
      reportError('Unhandled exception: $error');
      stderr.writeln('Unhandled exception: $error\n$stack');
    },
  );
}

class _TrackedTimer implements Timer {
  _TrackedTimer(this._timer, this._onCancel);

  final Timer _timer;
  final void Function() _onCancel;

  @override
  void cancel() {
    _timer.cancel();
    _onCancel();
  }

  @override
  bool get isActive => _timer.isActive;

  @override
  int get tick => _timer.tick;
}

/// The binding for an app under Bombadil: [WidgetsFlutterBinding], except
/// that the driver runs frames itself, on a [FrameClock], and watches the
/// app's platform messages.
///
/// The engine never runs a frame. When the app asks for one, the binding
/// runs it in the next turn of the event loop, the way
/// `PlatformDispatcher.scheduleWarmUpFrame` does: `handleBeginFrame`, then
/// microtasks, then `handleDrawFrame`. The frame builds, lays out, paints
/// and updates the semantics tree. Unless [rasterize] is set, it doesn't
/// send the scene to the engine (the first frame stays deferred), so
/// nothing is rasterized: the driver reads semantics, not pixels.
class BombadilBinding extends BindingBase
    with
        GestureBinding,
        SchedulerBinding,
        ServicesBinding,
        PaintingBinding,
        SemanticsBinding,
        RendererBinding,
        WidgetsBinding
    implements Activity {
  BombadilBinding._(
    this.monitor,
    this.driverZone,
    this.viewSize,
    this.viewPixelRatio,
    this.rasterize,
  );

  /// Creates the binding, in the app's zone, which [monitor] watches.
  /// [driverZone] is where the driver's own timers go. The app's view is
  /// [size] logical pixels at [devicePixelRatio], whatever the engine says.
  /// Frames go to the engine, which rasterizes them, only if [rasterize].
  static BombadilBinding ensureInitialized(
    AppMonitor monitor,
    Zone driverZone, {
    Size size = const Size(1024, 768),
    double devicePixelRatio = 1,
    bool rasterize = false,
  }) {
    return _instance ??= BombadilBinding._(
      monitor,
      driverZone,
      size,
      devicePixelRatio,
      rasterize,
    );
  }

  final Size viewSize;
  final bool rasterize;
  final double viewPixelRatio;

  /// The engine's dispatcher, with the view's metrics overridden.
  late final TestPlatformDispatcher _dispatcher = TestPlatformDispatcher(
    platformDispatcher: PlatformDispatcher.instance,
  );

  @override
  TestPlatformDispatcher get platformDispatcher => _dispatcher;

  static BombadilBinding? _instance;

  static BombadilBinding get instance => _instance!;

  final AppMonitor monitor;
  final Zone driverZone;
  late final Zone appZone;
  final clock = FrameClock();
  final platform = PlatformState();
  late final DriverMessenger messenger;

  bool _framePending = false;
  int _pointer = 1 << 20;

  @override
  void initInstances() {
    appZone = Zone.current;
    _dispatcher.implicitView!
      ..devicePixelRatio = viewPixelRatio
      ..physicalSize = viewSize * viewPixelRatio;
    super.initInstances();
    // Frames lay out and paint, and the driver flushes semantics itself
    // ([_drawFrame]), but they never reach the engine, which would
    // rasterize every one of them in software on this thread.
    if (!rasterize) deferFirstFrame();
  }

  @override
  BinaryMessenger createBinaryMessenger() => messenger = DriverMessenger(
    super.createBinaryMessenger(),
    platform,
    onChange: monitor.notify,
  );

  @override
  void ensureFrameCallbacksRegistered() {
    // The engine doesn't run frames; [_pump] does.
  }

  @override
  void scheduleFrame() {
    super.scheduleFrame();
    _pump();
  }

  @override
  void scheduleForcedFrame() {
    super.scheduleForcedFrame();
    _pump();
  }

  @override
  void scheduleWarmUpFrame() => scheduleFrame();

  void _pump() {
    if (_framePending || !hasScheduledFrame) return;
    _framePending = true;
    monitor.notify();
    driverZone.run(() => Timer(clock.delay, _beginFrame));
  }

  void _beginFrame() {
    if (!hasScheduledFrame || schedulerPhase != SchedulerPhase.idle) {
      _framePending = false;
      _pump();
      return;
    }
    appZone.run(() => handleBeginFrame(clock.next()));
    // Microtasks from the transient callbacks run before the timer.
    Timer.run(_drawFrame);
  }

  void _drawFrame() {
    appZone.run(handleDrawFrame);
    if (!rasterize) appZone.run(rootPipelineOwner.flushSemantics);
    _framePending = false;
    monitor.notify();
    _pump();
  }

  @override
  String? get busy {
    if (hasScheduledFrame || schedulerPhase != SchedulerPhase.idle) {
      return 'frame';
    }
    if (messenger.pending > 0) return 'platform message';
    if (monitor.timers > 0) return 'timer';
    return null;
  }

  @override
  Future<void> get changed => monitor.changed;

  /// A touch tap at [position], in logical pixels.
  Future<void> tapAt(Offset position) async {
    final pointer = _pointer++;
    final viewId = platformDispatcher.implicitView!.viewId;
    appZone.run(
      () => handlePointerEvent(
        PointerDownEvent(
          pointer: pointer,
          viewId: viewId,
          position: position,
          timeStamp: clock.now,
        ),
      ),
    );
    await Future<void>(() {});
    appZone.run(
      () => handlePointerEvent(
        PointerUpEvent(
          pointer: pointer,
          viewId: viewId,
          position: position,
          timeStamp: clock.now,
        ),
      ),
    );
  }

  /// The system back button: the app's navigators pop, or the app asks to
  /// close.
  Future<void> back() => appZone.run(handlePopRoute);

  /// Tells the app it's in the foreground.
  void resume() => handleAppLifecycleStateChanged(AppLifecycleState.resumed);
}
