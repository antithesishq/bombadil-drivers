import 'dart:async';

/// What the driver can see of the app's pending work.
abstract interface class Activity {
  /// Why the app isn't idle (such as `frame`), or null if it is.
  String? get busy;

  /// Completes when something that [busy] depends on changes: a frame is
  /// requested or ends, the platform answers a message, a queued timer
  /// runs.
  Future<void> get changed;
}

/// Waits until [app] is idle, and returns true; or until [cap] has passed,
/// and returns false.
///
/// Each round first yields to the event loop, so that everything already
/// queued (tasks, and the microtasks they schedule) runs. Then, if [app]
/// is still busy, it waits for its next [Activity.changed]: settling
/// follows the app's own signals, and [cap] is only a fallback for apps
/// that never go idle, like one showing a spinner.
///
/// [yieldToEventLoop] defaults to a zero-delay timer. [onWait] hears why
/// each wait happens, for timing logs.
Future<bool> settle(
  Activity app, {
  required Duration cap,
  Future<void> Function()? yieldToEventLoop,
  void Function(String reason)? onWait,
}) async {
  final elapsed = Stopwatch()..start();
  final yield_ = yieldToEventLoop ?? () => Future<void>(() {});
  while (true) {
    await yield_();
    final busy = app.busy;
    if (busy == null) return true;
    onWait?.call(busy);
    final left = cap - elapsed.elapsed;
    if (left <= Duration.zero) return false;
    final timeout = Completer<void>();
    final timer = Timer(left, timeout.complete);
    await Future.any([app.changed, timeout.future]);
    timer.cancel();
  }
}
