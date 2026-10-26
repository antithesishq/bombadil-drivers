import 'dart:async';
import 'dart:ui' as ui;

import 'package:bombadil_flutter/src/flutter/actions.dart';
import 'package:bombadil_flutter/src/flutter/semantics.dart';
import 'package:bombadil_flutter/src/flutter/settle.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';

/// The test app's view, as the driver sees it.
SemanticsSnapshot capture(WidgetTester tester) {
  final view = tester.view;
  return SemanticsSnapshot.capture(
    tester.binding.renderViews.first.owner!.semanticsOwner!.rootSemanticsNode,
    screen: view.physicalSize / view.devicePixelRatio,
    devicePixelRatio: view.devicePixelRatio,
  );
}

List<Map<String, Object?>> flatten(Map<String, Object?> node) => [
  node,
  for (final child in node['children']! as List)
    ...flatten(child as Map<String, Object?>),
];

Map<String, Object?> labelled(SemanticsSnapshot snapshot, String label) =>
    flatten(snapshot.tree!).singleWhere((n) => n['label'] == label);

/// An [ActionTarget] on a widget test.
class TesterTarget implements ActionTarget {
  TesterTarget(this.tester) : snapshot = capture(tester);

  final WidgetTester tester;
  var backs = 0;

  @override
  SemanticsSnapshot snapshot;

  @override
  SemanticsSnapshot refresh() => snapshot = capture(tester);

  @override
  ui.Size get screen => tester.view.physicalSize / tester.view.devicePixelRatio;

  @override
  Future<void> tapAt(Offset position) => tester.tapAt(position);

  @override
  Future<void> back() async {
    backs++;
    await tester.binding.handlePopRoute();
  }

  @override
  Future<void> settle() => tester.pumpAndSettle();
}

class Counter extends StatefulWidget {
  const Counter({super.key, this.extra = false});

  /// Adds an anonymous, tappable node before the others.
  final bool extra;

  @override
  State<Counter> createState() => _CounterState();
}

class _CounterState extends State<Counter> {
  var count = 0;
  var text = '';
  var slider = 0.5;

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: Scaffold(
      body: ListView(
        children: [
          if (widget.extra)
            IconButton(onPressed: () {}, icon: const Icon(Icons.add)),
          Text('Count $count'),
          ElevatedButton(
            onPressed: () => setState(() => count++),
            child: const Text('Increment'),
          ),
          // Labelled, but without a tap action: only a pointer tap works.
          Semantics(
            label: 'Add ten',
            child: Listener(
              behavior: HitTestBehavior.opaque,
              onPointerUp: (_) => setState(() => count += 10),
              child: const SizedBox(width: 100, height: 40),
            ),
          ),
          TextField(
            decoration: const InputDecoration(labelText: 'Name'),
            onChanged: (value) => setState(() => text = value),
          ),
          Text('Hello $text'),
          Slider(
            value: slider,
            onChanged: (value) => setState(() => slider = value),
          ),
          for (final row in ['One', 'Two'])
            ListTile(
              title: Text(row),
              onTap: () {},
              trailing: IconButton(
                tooltip: 'Delete',
                onPressed: () {},
                icon: const Icon(Icons.delete),
              ),
            ),
        ],
      ),
    ),
  );
}

void main() {
  group('semantics', () {
    testWidgets('reports labels, flags, actions and logical rects', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(1200, 900);
      tester.view.devicePixelRatio = 1.5;
      addTearDown(tester.view.reset);
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(const Counter());

      final snapshot = capture(tester);
      final button = labelled(snapshot, 'Increment');
      expect(button['flags'], containsAll(['button', 'enabled', 'focusable']));
      expect(button['actions'], containsAll(['tap', 'focus']));
      expect(button['visible'], isTrue);
      final rect = tester.getRect(find.byType(ElevatedButton));
      expect(button['rect'], [
        rect.left.roundToDouble(),
        rect.top.roundToDouble(),
        rect.width.roundToDouble(),
        rect.height.roundToDouble(),
      ]);
      expect(snapshot.tree!['rect'], [0, 0, 800, 600]);
      expect(labelled(snapshot, 'Count 0').containsKey('hint'), isFalse);
      final field = flatten(
        snapshot.tree!,
      ).singleWhere((n) => (n['flags']! as List).contains('textField'));
      expect(field['label'], 'Name');
      final slider = flatten(
        snapshot.tree!,
      ).singleWhere((n) => (n['flags']! as List).contains('slider'));
      expect(slider['value'], '50%');
      expect(slider['actions'], containsAll(['increase', 'decrease']));
      final list = flatten(
        snapshot.tree!,
      ).firstWhere((n) => n.containsKey('scroll'));
      expect(list['scroll'], containsPair('position', 0));
      handle.dispose();
    });

    testWidgets('marks nodes off screen as not visible', (tester) async {
      tester.view.physicalSize = const Size(800, 150);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(const Counter());
      final snapshot = capture(tester);
      expect(labelled(snapshot, 'Count 0')['visible'], isTrue);
      // ListView keeps a cache extent of nodes beyond the screen.
      final offscreen = flatten(
        snapshot.tree!,
      ).where((n) => n['visible'] == false && n.containsKey('label'));
      expect(offscreen, isNotEmpty);
      handle.dispose();
    });

    testWidgets('derives ids from what nodes are, not from build order', (
      tester,
    ) async {
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(const Counter());
      final before = capture(tester);
      await tester.pumpWidget(const Counter(key: ValueKey(2), extra: true));
      final after = capture(tester);

      for (final label in ['Increment', 'Count 0', 'Add ten']) {
        expect(labelled(after, label)['id'], labelled(before, label)['id']);
      }
      // Flutter's ids changed: the tree was rebuilt.
      expect(
        labelled(after, 'Increment')['flutterId'],
        isNot(labelled(before, 'Increment')['flutterId']),
      );
      // The identical delete buttons differ by the row they are in.
      final deletes = flatten(
        after.tree!,
      ).where((n) => n['tooltip'] == 'Delete').map((n) => n['id']).toList();
      expect(deletes, hasLength(2));
      expect(deletes.toSet(), hasLength(2));
      expect(deletes.every((id) => !(id! as String).contains('-')), isTrue);
      handle.dispose();
    });

    test('hashes with FNV-1a', () {
      expect(fnv1a(''), '811c9dc5');
      expect(fnv1a('a'), 'e40c292c');
    });

    test('names actions and flags', () {
      expect(
        actionNames(
          SemanticsAction.tap.index | SemanticsAction.scrollDown.index,
        ),
        ['tap', 'scrollDown'],
      );
    });
  });

  group('actions', () {
    test('parses the action shapes', () {
      expect(DriverAction.parse('Back'), isA<Back>());
      expect(
        DriverAction.parse({
          'Tap': {'node': 'a1'},
        }),
        isA<NodeAction>()
            .having((a) => a.node, 'node', 'a1')
            .having((a) => a.action, 'action', SemanticsAction.tap),
      );
      expect(
        DriverAction.parse({
          'ScrollDown': {'node': 'a1'},
        }),
        isA<NodeAction>().having(
          (a) => a.action,
          'action',
          SemanticsAction.scrollDown,
        ),
      );
      expect(
        DriverAction.parse({
          'SetText': {'node': 'a1', 'text': 'hi'},
        }),
        isA<SetText>().having((a) => a.text, 'text', 'hi'),
      );
      expect(
        DriverAction.parse({
          'PointerTap': {'x': 1, 'y': 2.5},
        }),
        isA<PointerTap>().having(
          (a) => a.position,
          'position',
          const Offset(1, 2.5),
        ),
      );
      for (final bad in [
        'Forward',
        {'Tap': {}},
        {
          'Tap': {'node': 3},
        },
        {
          'Fly': {'node': 'a1'},
        },
        {
          'Tap': {'node': 'a'},
          'Back': {},
        },
      ]) {
        expect(() => DriverAction.parse(bad), throwsFormatException);
      }
    });

    testWidgets('taps, sets text and adjusts through semantics', (
      tester,
    ) async {
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(const Counter());
      final target = TesterTarget(tester);
      String id(String label) =>
          labelled(target.snapshot, label)['id']! as String;

      await perform(NodeAction(id('Increment'), SemanticsAction.tap), target);
      await tester.pumpAndSettle();
      expect(find.text('Count 1'), findsOneWidget);

      // No tap action: a pointer tap at the node's centre.
      target.refresh();
      final addTen = labelled(target.snapshot, 'Add ten');
      expect(addTen['actions'], isNot(contains('tap')));
      await perform(
        NodeAction(addTen['id']! as String, SemanticsAction.tap),
        target,
      );
      await tester.pumpAndSettle();
      expect(find.text('Count 11'), findsOneWidget);

      target.refresh();
      final field =
          flatten(
                target.snapshot.tree!,
              ).singleWhere((n) => n['label'] == 'Name')['id']!
              as String;
      await perform(SetText(field, 'Bombadil'), target);
      await tester.pumpAndSettle();
      expect(find.text('Hello Bombadil'), findsOneWidget);

      target.refresh();
      final slider =
          flatten(target.snapshot.tree!).singleWhere(
                (n) => (n['flags']! as List).contains('slider'),
              )['id']!
              as String;
      await perform(NodeAction(slider, SemanticsAction.increase), target);
      await tester.pumpAndSettle();
      expect(
        tester.widget<Slider>(find.byType(Slider)).value,
        greaterThan(0.5),
      );
      handle.dispose();
    });

    testWidgets('reports actions that do not apply', (tester) async {
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(const Counter());
      final target = TesterTarget(tester);
      final count = labelled(target.snapshot, 'Count 0')['id']! as String;
      Future<String?> failure(DriverAction action) async {
        try {
          await perform(action, target);
        } on ActionError catch (e) {
          return e.message;
        }
        return null;
      }

      expect(
        await failure(const NodeAction('nope', SemanticsAction.tap)),
        'no node nope',
      );
      expect(
        await failure(NodeAction(count, SemanticsAction.scrollDown)),
        contains('scrollDown'),
      );
      expect(await failure(SetText(count, 'x')), contains('text'));
      handle.dispose();
    });

    testWidgets('goes back through the navigator', (tester) async {
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () => Navigator.of(
                context,
              ).push(MaterialPageRoute(builder: (_) => const Text('Second'))),
              child: const Text('Open'),
            ),
          ),
        ),
      );
      final target = TesterTarget(tester);
      await perform(
        NodeAction(
          labelled(target.snapshot, 'Open')['id']! as String,
          SemanticsAction.tap,
        ),
        target,
      );
      await tester.pumpAndSettle();
      expect(find.text('Second'), findsOneWidget);
      await perform(const Back(), target);
      await tester.pumpAndSettle();
      expect(find.text('Second'), findsNothing);
      expect(target.backs, 1);
      handle.dispose();
    });
  });

  group('settle', () {
    test('returns once the app is idle, following its signals', () async {
      final app = FakeActivity()..busy = 'frame';
      var done = false;
      final settled = settle(
        app,
        cap: const Duration(seconds: 5),
      ).then((s) => done = s);
      await pumpEventQueue();
      expect(done, isFalse);
      app.busy = 'timer';
      app.change();
      await pumpEventQueue();
      expect(done, isFalse);
      expect(app.waits, ['frame', 'timer']);
      app.busy = null;
      app.change();
      expect(await settled, isTrue);
    });

    test('drains queued work before deciding the app is idle', () async {
      final app = FakeActivity()..busy = 'frame';
      // Work already queued when settling starts.
      Timer.run(() => app.busy = null);
      expect(await settle(app, cap: const Duration(seconds: 5)), isTrue);
      expect(app.waits, isEmpty);
    });

    test('gives up after the cap, for apps that never go idle', () async {
      final app = FakeActivity()..busy = 'frame';
      final watch = Stopwatch()..start();
      expect(await settle(app, cap: const Duration(milliseconds: 50)), isFalse);
      expect(
        watch.elapsed,
        greaterThanOrEqualTo(const Duration(milliseconds: 50)),
      );
    });
  });
}

class FakeActivity implements Activity {
  @override
  String? busy;

  final waits = <String>[];
  var _changed = Completer<void>();

  @override
  Future<void> get changed {
    waits.add(busy!);
    return _changed.future;
  }

  void change() {
    _changed.complete();
    _changed = Completer<void>();
  }
}
