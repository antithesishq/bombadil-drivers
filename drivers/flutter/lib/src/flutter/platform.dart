import 'package:flutter/services.dart';

/// What the driver plays of the platform's side of the app's channels.
///
/// The app runs without an embedder that implements plugins, so the
/// messenger answers method calls that nothing answered with success and a
/// null result: plugin calls neither hang nor throw
/// `MissingPluginException`. It keeps the state of a few system channels
/// for the driver: the route, the clipboard and whether the app asked to
/// close.
class PlatformState {
  /// The route the app last reported (`routeInformationUpdated` on
  /// `flutter/navigation`, which `Router` and the root `Navigator` send).
  String? route;

  /// Whether the app called `SystemNavigator.pop` since the driver last
  /// looked.
  bool exitRequested = false;

  String? clipboard;

  /// The reply to a JSON method call on a system channel that the driver
  /// answers itself, or null to pass the call on to the engine.
  ByteData? handle(String channel, ByteData? message) {
    if (!const {'flutter/navigation', 'flutter/platform'}.contains(channel)) {
      return null;
    }
    final MethodCall call;
    try {
      call = const JSONMethodCodec().decodeMethodCall(message);
    } on Object {
      return null;
    }
    final args = call.arguments;
    Object? result;
    switch ((channel, call.method)) {
      case ('flutter/navigation', 'routeInformationUpdated'):
        if (args is Map) {
          route = (args['uri'] ?? args['location']) as String?;
        }
      case ('flutter/navigation', _):
        break;
      case ('flutter/platform', 'SystemNavigator.pop'):
        exitRequested = true;
      case ('flutter/platform', 'Clipboard.setData'):
        clipboard = args is Map ? args['text'] as String? : null;
      case ('flutter/platform', 'Clipboard.getData'):
        result = clipboard == null ? null : {'text': clipboard};
      case ('flutter/platform', 'Clipboard.hasStrings'):
        result = {'value': clipboard?.isNotEmpty ?? false};
      default:
        return null;
    }
    return const JSONMethodCodec().encodeSuccessEnvelope(result);
  }
}

/// A successful null reply to [message] on [channel], in the codec it
/// looks like it uses, or null if it doesn't look like a call.
ByteData? nullReply(String channel, ByteData? message) {
  if (message == null || message.lengthInBytes == 0) return null;
  // Pigeon host APIs: a standard-codec message, answered with `[null]`.
  if (channel.startsWith('dev.flutter.pigeon.')) {
    return ByteData.sublistView(Uint8List.fromList([12, 1, 0]));
  }
  final first = message.getUint8(0);
  // A JSON method call.
  if (first == 0x7b /* { */ ) {
    try {
      const JSONMethodCodec().decodeMethodCall(message);
      return const JSONMethodCodec().encodeSuccessEnvelope(null);
    } on Object {
      return null;
    }
  }
  // A standard method call starts with its method name, a string.
  if (first == 7) {
    return const StandardMethodCodec().encodeSuccessEnvelope(null);
  }
  return null;
}

/// The app's [BinaryMessenger], wrapped: the driver answers some system
/// calls itself ([PlatformState]), fills in null replies ([nullReply]),
/// and counts the messages the platform hasn't answered yet, which the
/// app is waiting for.
class DriverMessenger implements BinaryMessenger {
  DriverMessenger(this._inner, this.platform, {required this.onChange});

  final BinaryMessenger _inner;
  final PlatformState platform;

  /// Called when [pending] changes.
  final void Function() onChange;

  /// Messages sent to the platform and not answered yet.
  int pending = 0;

  @override
  Future<ByteData?>? send(String channel, ByteData? message) {
    final handled = platform.handle(channel, message);
    if (handled != null) return Future.value(handled);
    final reply = _inner.send(channel, message);
    if (reply == null) return null;
    pending++;
    onChange();
    return reply
        .then((data) => data ?? nullReply(channel, message))
        .whenComplete(() {
          pending--;
          onChange();
        });
  }

  @override
  void setMessageHandler(String channel, MessageHandler? handler) =>
      _inner.setMessageHandler(channel, handler);

  @override
  // ignore: deprecated_member_use
  Future<void> handlePlatformMessage(
    String channel,
    ByteData? data,
    PlatformMessageResponseCallback? callback,
  ) =>
      // ignore: deprecated_member_use
      _inner.handlePlatformMessage(channel, data, callback);
}
