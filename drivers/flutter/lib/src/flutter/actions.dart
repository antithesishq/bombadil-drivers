import 'dart:ui' as ui;

import 'package:flutter/semantics.dart';
import 'package:flutter/widgets.dart';

import 'semantics.dart';

/// An action from the specification. Actions are externally tagged JSON:
/// `{"Tap":{"node":"1f0c2a9e"}}`, `{"SetText":{"node":"…","text":"abc"}}`,
/// `{"CustomAction":{"node":"…","action":7}}`, `{"PointerTap":{"x":10,"y":20}}`
/// and `"Back"`. See [DriverAction.parse] for the semantics actions.
sealed class DriverAction {
  const DriverAction();

  /// Semantics actions that take a node and nothing else, by tag.
  static const semanticsActions = {
    'Tap': SemanticsAction.tap,
    'LongPress': SemanticsAction.longPress,
    'ScrollUp': SemanticsAction.scrollUp,
    'ScrollDown': SemanticsAction.scrollDown,
    'ScrollLeft': SemanticsAction.scrollLeft,
    'ScrollRight': SemanticsAction.scrollRight,
    'Increase': SemanticsAction.increase,
    'Decrease': SemanticsAction.decrease,
    'Dismiss': SemanticsAction.dismiss,
    'Focus': SemanticsAction.focus,
    'Expand': SemanticsAction.expand,
    'Collapse': SemanticsAction.collapse,
    'ShowOnScreen': SemanticsAction.showOnScreen,
    'Copy': SemanticsAction.copy,
    'Paste': SemanticsAction.paste,
  };

  /// Parses an action. Malformed actions throw [FormatException], which
  /// ends the run: the specification and the driver disagree.
  static DriverAction parse(Object? json) {
    if (json == 'Back') return const Back();
    FormatException bad() => FormatException('unknown action: $json');
    if (json is! Map<String, Object?> || json.length != 1) throw bad();
    final MapEntry(key: tag, value: body) = json.entries.single;
    if (body is! Map<String, Object?>) throw bad();
    String node() => switch (body['node']) {
      final String id => id,
      _ => throw FormatException('$tag needs a node id: $json'),
    };
    if (semanticsActions[tag] case final action?) {
      return NodeAction(node(), action);
    }
    return switch ((tag, body)) {
      ('SetText', {'text': final String text}) => SetText(node(), text),
      ('CustomAction', {'action': final int id}) => CustomAction(node(), id),
      ('PointerTap', {'x': final num x, 'y': final num y}) => PointerTap(
        Offset(x.toDouble(), y.toDouble()),
      ),
      _ => throw bad(),
    };
  }
}

class NodeAction extends DriverAction {
  const NodeAction(this.node, this.action);

  final String node;
  final SemanticsAction action;
}

class SetText extends DriverAction {
  const SetText(this.node, this.text);

  final String node;
  final String text;
}

class CustomAction extends DriverAction {
  const CustomAction(this.node, this.id);

  final String node;
  final int id;
}

class PointerTap extends DriverAction {
  const PointerTap(this.position);

  final Offset position;
}

class Back extends DriverAction {
  const Back();
}

/// An action that doesn't apply to the current state, such as one on a
/// node that is gone. The next state reports it; the run goes on.
class ActionError implements Exception {
  const ActionError(this.message);

  final String message;

  @override
  String toString() => message;
}

/// What actions act on: the app, as the driver sees it.
abstract interface class ActionTarget {
  /// The tree as of the last state.
  SemanticsSnapshot get snapshot;

  /// Captures the tree anew, and makes it [snapshot].
  SemanticsSnapshot refresh();

  /// The view's size in logical pixels.
  ui.Size get screen;

  /// A touch tap at [position], in logical pixels.
  Future<void> tapAt(Offset position);

  /// The system back button.
  Future<void> back();

  /// Waits for the app to respond to what happened so far.
  Future<void> settle();
}

/// Performs [action] on [target]. Throws [ActionError] if it doesn't apply.
Future<void> perform(DriverAction action, ActionTarget target) async {
  switch (action) {
    case NodeAction(:final node, action: SemanticsAction.tap):
      final semantics = _lookup(target, node);
      if (_supports(semantics, SemanticsAction.tap)) {
        _perform(semantics, SemanticsAction.tap);
      } else {
        await _tapNode(target, node);
      }
    case NodeAction(:final node, :final action):
      final semantics = _lookup(target, node);
      if (!_supports(semantics, action)) {
        throw ActionError('node $node does not support ${action.name}');
      }
      _perform(semantics, action);
    case SetText(:final node, :final text):
      var semantics = _lookup(target, node);
      // Text fields take text once focused.
      if (!_supports(semantics, SemanticsAction.setText)) {
        if (_supports(semantics, SemanticsAction.tap)) {
          _perform(semantics, SemanticsAction.tap);
        } else {
          await _tapNode(target, node);
        }
        await target.settle();
        semantics = target.refresh().nodes[node] ?? semantics;
      }
      if (semantics.attached && _supports(semantics, SemanticsAction.setText)) {
        _perform(semantics, SemanticsAction.setText, text);
      } else if (_focusedEditable() case final editable?) {
        editable.updateEditingValue(
          TextEditingValue(
            text: text,
            selection: TextSelection.collapsed(offset: text.length),
          ),
        );
      } else {
        throw ActionError('node $node does not take text');
      }
    case CustomAction(:final node, :final id):
      final semantics = _lookup(target, node);
      if (!(semantics.getSemanticsData().customSemanticsActionIds?.contains(
            id,
          ) ??
          false)) {
        throw ActionError('node $node has no custom action $id');
      }
      _perform(semantics, SemanticsAction.customAction, id);
    case PointerTap(:final position):
      await target.tapAt(position);
    case Back():
      await target.back();
  }
}

SemanticsNode _lookup(ActionTarget target, String id) {
  final node = target.snapshot.nodes[id];
  if (node == null) throw ActionError('no node $id');
  if (!node.attached) throw ActionError('node $id is gone');
  return node;
}

bool _supports(SemanticsNode node, SemanticsAction action) =>
    node.getSemanticsData().hasAction(action);

void _perform(SemanticsNode node, SemanticsAction action, [Object? args]) =>
    node.owner!.performAction(node.id, action, args);

Future<void> _tapNode(ActionTarget target, String id) async {
  final centre = target.snapshot.visibleCentre(id, target.screen);
  if (centre == null) {
    throw ActionError('node $id cannot be tapped and is not on screen');
  }
  await target.tapAt(centre);
}

EditableTextState? _focusedEditable() => FocusManager
    .instance
    .primaryFocus
    ?.context
    ?.findAncestorStateOfType<EditableTextState>();
