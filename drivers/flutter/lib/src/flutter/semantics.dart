import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';

/// The app's semantics tree at one moment, as the state's `tree`, with the
/// nodes it names.
///
/// Node ids are derived from what a node is rather than from Flutter's
/// node ids, which depend on the order in which the app built its widgets.
/// See [nodeKey].
class SemanticsSnapshot {
  SemanticsSnapshot._(this.tree, this.nodes, this.rects);

  /// An empty snapshot, for an app without a semantics tree yet.
  SemanticsSnapshot.empty() : this._(null, const {}, const {});

  /// Captures the tree under [root]. [screen] is the view's size in logical
  /// pixels and [devicePixelRatio] its ratio of physical to logical pixels:
  /// the root node's transform scales to physical pixels, and rects are
  /// reported in logical ones.
  factory SemanticsSnapshot.capture(
    SemanticsNode? root, {
    required ui.Size screen,
    required double devicePixelRatio,
  }) {
    if (root == null) return SemanticsSnapshot.empty();
    final nodes = <String, SemanticsNode>{};
    final rects = <String, Rect>{};
    final seen = <String, int>{};
    final scale = Matrix4.diagonal3Values(
      1 / devicePixelRatio,
      1 / devicePixelRatio,
      1,
    );

    Map<String, Object?> visit(
      SemanticsNode node,
      Matrix4 parent,
      String tagged,
      String tappable,
      String parentId,
    ) {
      final data = node.getSemanticsData();
      final transform = node.transform == null
          ? parent
          : parent.multiplied(node.transform!);
      final rect = MatrixUtils.transformRect(transform, node.rect);

      final hash = fnv1a(nodeKey(data, tagged, tappable, parentId));
      final n = seen[hash] ?? 0;
      seen[hash] = n + 1;
      final id = n == 0 ? hash : '$hash-$n';
      nodes[id] = node;
      rects[id] = rect;

      final flags = flagNames(data.flagsCollection);
      final onScreen =
          rect.right > rect.left &&
          rect.bottom > rect.top &&
          rect.right > 0 &&
          rect.bottom > 0 &&
          rect.left < screen.width &&
          rect.top < screen.height;
      final childTagged = data.identifier.isEmpty ? tagged : data.identifier;
      final childTappable = data.hasAction(SemanticsAction.tap)
          ? data.label
          : tappable;
      // Like the engine's copy of the tree: a node that merges its
      // descendants carries their data and has no children.
      final children = node.mergeAllDescendantsIntoThisNode
          ? const <SemanticsNode>[]
          : node.debugListChildrenInOrder(
              DebugSemanticsDumpOrder.traversalOrder,
            );
      return {
        'id': id,
        'flutterId': node.id,
        if (data.label.isNotEmpty) 'label': data.label,
        if (data.value.isNotEmpty) 'value': data.value,
        if (data.hint.isNotEmpty) 'hint': data.hint,
        if (data.tooltip.isNotEmpty) 'tooltip': data.tooltip,
        if (data.identifier.isNotEmpty) 'identifier': data.identifier,
        'flags': flags,
        'actions': actionNames(data.actions),
        if (data.customSemanticsActionIds case final ids? when ids.isNotEmpty)
          'customActions': [
            for (final id in ids)
              {'id': id, 'label': CustomSemanticsAction.getAction(id)?.label},
          ],
        'rect': [
          rect.left.roundToDouble(),
          rect.top.roundToDouble(),
          rect.width.roundToDouble(),
          rect.height.roundToDouble(),
        ],
        'visible': onScreen && !data.flagsCollection.isHidden,
        if (data.scrollPosition case final position?)
          'scroll': {
            'position': position.roundToDouble(),
            'min': (data.scrollExtentMin ?? 0).roundToDouble(),
            'max': (data.scrollExtentMax ?? 0).roundToDouble(),
          },
        'children': [
          for (final child in children)
            visit(child, transform, childTagged, childTappable, id),
        ],
      };
    }

    final tree = visit(root, scale, '', '', '');
    return SemanticsSnapshot._(tree, nodes, rects);
  }

  /// The tree as JSON, or null if the app has none yet.
  final Map<String, Object?>? tree;

  /// The nodes in [tree], by id.
  final Map<String, SemanticsNode> nodes;

  /// Each node's bounding box on screen, in logical pixels, by id.
  final Map<String, Rect> rects;

  /// The centre of the part of node [id] that is on a [screen] of the given
  /// size, or null if none of it is.
  Offset? visibleCentre(String id, ui.Size screen) {
    final rect = rects[id]?.intersect(Offset.zero & screen);
    if (rect == null || rect.width <= 0 || rect.height <= 0) return null;
    return rect.center;
  }
}

/// What a node is, for its id: the nearest ancestor's [SemanticsData.identifier]
/// ([tagged]), its own identifier, role, role flags and label, and the label
/// of its nearest tappable ancestor ([tappable]), which tells apart, say,
/// the identical "Delete" buttons of different rows. A node with neither
/// label nor identifier, such as a container or an icon button, is told
/// apart by its parent's id ([parentId]) instead, so its id stays put when
/// anonymous nodes elsewhere come and go.
///
/// Not its position, its value or its state (enabled, checked, focused,
/// the actions it supports), so the same node keeps its id as it scrolls,
/// changes or is replayed in another run. Nodes that still look alike get
/// `-1`, `-2`, ... in traversal order.
String nodeKey(
  SemanticsData data,
  String tagged,
  String tappable,
  String parentId,
) {
  final f = data.flagsCollection;
  final roles = [
    if (f.isButton) 'button',
    if (f.isTextField) 'textField',
    if (f.isLink) 'link',
    if (f.isHeader) 'header',
    if (f.isImage) 'image',
    if (f.isSlider) 'slider',
    if (f.isKeyboardKey) 'keyboardKey',
    if (f.isInMutuallyExclusiveGroup) 'inMutuallyExclusiveGroup',
    if (f.isChecked != ui.CheckedState.none) 'checkable',
    if (f.isToggled != ui.Tristate.none) 'toggleable',
    if (f.scopesRoute) 'scopesRoute',
    if (f.namesRoute) 'namesRoute',
    if (f.isObscured) 'obscured',
  ];
  return [
    tagged,
    data.identifier,
    data.role.name,
    roles.join(','),
    data.label,
    tappable,
    if (data.label.isEmpty && data.identifier.isEmpty) parentId,
  ].join('\u0000');
}

/// The 32-bit FNV-1a hash of [s]'s UTF-16 code units, as 8 hex digits.
String fnv1a(String s) {
  var h = 0x811c9dc5;
  for (final unit in s.codeUnits) {
    h ^= unit;
    h = (h * 0x01000193) & 0xffffffff;
  }
  return h.toRadixString(16).padLeft(8, '0');
}

/// Names of the set flags, such as `button` and `enabled`.
List<String> flagNames(ui.SemanticsFlags f) {
  String? tristate(ui.Tristate t, String yes, String no) => switch (t) {
    ui.Tristate.isTrue => yes,
    ui.Tristate.isFalse => no,
    ui.Tristate.none => null,
  };
  return [
    ?switch (f.isChecked) {
      ui.CheckedState.isTrue => 'checked',
      ui.CheckedState.isFalse => 'unchecked',
      ui.CheckedState.mixed => 'mixed',
      ui.CheckedState.none => null,
    },
    ?tristate(f.isSelected, 'selected', 'unselected'),
    ?tristate(f.isEnabled, 'enabled', 'disabled'),
    ?tristate(f.isToggled, 'toggled', 'untoggled'),
    ?tristate(f.isExpanded, 'expanded', 'collapsed'),
    ?tristate(f.isRequired, 'required', 'optional'),
    ?tristate(f.isFocused, 'focused', 'focusable'),
    if (f.isButton) 'button',
    if (f.isTextField) 'textField',
    if (f.isInMutuallyExclusiveGroup) 'inMutuallyExclusiveGroup',
    if (f.isHeader) 'header',
    if (f.isObscured) 'obscured',
    if (f.scopesRoute) 'scopesRoute',
    if (f.namesRoute) 'namesRoute',
    if (f.isHidden) 'hidden',
    if (f.isImage) 'image',
    if (f.isLiveRegion) 'liveRegion',
    if (f.hasImplicitScrolling) 'implicitScrolling',
    if (f.isMultiline) 'multiline',
    if (f.isReadOnly) 'readOnly',
    if (f.isLink) 'link',
    if (f.isSlider) 'slider',
    if (f.isKeyboardKey) 'keyboardKey',
  ];
}

/// Names of the actions in the [ui.SemanticsAction] bit set [actions],
/// such as `tap` and `scrollDown`.
List<String> actionNames(int actions) => [
  for (final action in _actionsByBit)
    if (actions & action.index != 0) action.name,
];

final _actionsByBit = ui.SemanticsAction.values
  ..sort((a, b) => a.index.compareTo(b.index));
