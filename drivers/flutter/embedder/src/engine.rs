//! Hosts a Flutter app with the engine's embedder API: software rendering
//! to nowhere, a platform task runner on the calling thread, vsync on
//! demand, platform channels and the semantics tree.

#![allow(non_upper_case_globals)]

use std::cmp::Reverse;
use std::collections::{BinaryHeap, VecDeque};
use std::ffi::{c_char, c_void, CStr, CString};
use std::ptr;
use std::sync::atomic::{AtomicPtr, Ordering};
use std::sync::{Condvar, Mutex, MutexGuard};
use std::thread::{self, ThreadId};
use std::time::Duration;

use crate::channels::Platform;
use crate::ffi::*;
use crate::semantics::{self, Node, Tree};

const FRAME_NANOS: u64 = 16_666_667;

pub fn now() -> u64 {
    unsafe { FlutterEngineGetCurrentTime() }
}

pub fn millis(ms: u64) -> u64 {
    ms * 1_000_000
}

struct Task {
    target: u64,
    seq: u64,
    task: FlutterTask,
}

// The engine allows running a task on the platform thread that it was
// posted from any thread.
unsafe impl Send for Task {}

impl PartialEq for Task {
    fn eq(&self, other: &Self) -> bool {
        (self.target, self.seq) == (other.target, other.seq)
    }
}
impl Eq for Task {}
impl PartialOrd for Task {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for Task {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        (self.target, self.seq).cmp(&(other.target, other.seq))
    }
}

/// Work for the platform thread, which other threads hand over.
#[derive(Default)]
struct Queue {
    tasks: BinaryHeap<Reverse<Task>>,
    seq: u64,
    vsync_batons: Vec<isize>,
    last_vsync: u64,
    lines: VecDeque<String>,
    input_closed: bool,
    /// When the app last asked for a frame or changed its semantics.
    last_activity: u64,
}

/// What the app has told the driver.
#[derive(Default)]
pub struct App {
    pub tree: Tree,
    pub platform: Platform,
    /// Channels the app has sent messages on, to log each once.
    pub channels_seen: std::collections::HashSet<String>,
}

pub struct Host {
    engine: AtomicPtr<_FlutterEngine>,
    platform_thread: ThreadId,
    queue: Mutex<Queue>,
    wake: Condvar,
    app: Mutex<App>,
    pub width: usize,
    pub height: usize,
}

pub struct Options {
    pub assets: String,
    pub icu_data: String,
    pub width: usize,
    pub height: usize,
    pub engine_args: Vec<String>,
}

fn check(result: FlutterEngineResult, what: &str) -> Result<(), String> {
    if result == kSuccess {
        Ok(())
    } else {
        Err(format!("{what} failed with FlutterEngineResult {result}"))
    }
}

fn lossy(text: *const c_char) -> String {
    if text.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(text) }
            .to_string_lossy()
            .into_owned()
    }
}

impl Host {
    /// Starts the engine on the calling thread, which becomes the platform
    /// thread: call every other method from it too, except the ones that
    /// say otherwise.
    pub fn start(options: Options) -> Result<&'static Host, String> {
        let host: &'static Host = Box::leak(Box::new(Host {
            engine: AtomicPtr::new(ptr::null_mut()),
            platform_thread: thread::current().id(),
            queue: Mutex::new(Queue {
                last_activity: now(),
                ..Default::default()
            }),
            wake: Condvar::new(),
            app: Mutex::new(App::default()),
            width: options.width,
            height: options.height,
        }));
        let user_data = host as *const Host as *mut c_void;

        let renderer = FlutterRendererConfig {
            type_: kSoftware,
            __bindgen_anon_1: FlutterRendererConfig__bindgen_ty_1 {
                software: FlutterSoftwareRendererConfig {
                    struct_size: size_of::<FlutterSoftwareRendererConfig>(),
                    surface_present_callback: Some(present),
                },
            },
        };
        let platform_runner = FlutterTaskRunnerDescription {
            struct_size: size_of::<FlutterTaskRunnerDescription>(),
            user_data,
            runs_task_on_current_thread_callback: Some(runs_on_platform_thread),
            post_task_callback: Some(post_task),
            identifier: 1,
            destruction_callback: None,
        };
        let runners = FlutterCustomTaskRunners {
            struct_size: size_of::<FlutterCustomTaskRunners>(),
            platform_task_runner: &platform_runner,
            ..Default::default()
        };
        let strings = |values: &[String]| -> Vec<CString> {
            values
                .iter()
                .map(|v| CString::new(v.as_str()).expect("no NUL in arguments"))
                .collect()
        };
        let assets = CString::new(options.assets).map_err(|e| e.to_string())?;
        let icu_data = CString::new(options.icu_data).map_err(|e| e.to_string())?;
        // The engine skips argv[0].
        let args = strings(
            &[
                vec!["bombadil-flutter-driver".to_string()],
                options.engine_args,
            ]
            .concat(),
        );
        let argv: Vec<*const c_char> = args.iter().map(|a| a.as_ptr()).collect();
        let log_tag = CString::new("flutter").unwrap();
        let project = FlutterProjectArgs {
            struct_size: size_of::<FlutterProjectArgs>(),
            assets_path: assets.as_ptr(),
            icu_data_path: icu_data.as_ptr(),
            command_line_argc: argv.len() as i32,
            command_line_argv: argv.as_ptr(),
            platform_message_callback: Some(platform_message),
            vsync_callback: Some(vsync),
            custom_task_runners: &runners,
            log_message_callback: Some(log_message),
            log_tag: log_tag.as_ptr(),
            update_semantics_callback2: Some(update_semantics),
            ..Default::default()
        };
        let mut engine = ptr::null_mut();
        check(
            unsafe {
                FlutterEngineRun(
                    FLUTTER_ENGINE_VERSION as usize,
                    &renderer,
                    &project,
                    user_data,
                    &mut engine,
                )
            },
            "FlutterEngineRun",
        )?;
        host.engine.store(engine, Ordering::SeqCst);

        let metrics = FlutterWindowMetricsEvent {
            struct_size: size_of::<FlutterWindowMetricsEvent>(),
            width: options.width,
            height: options.height,
            pixel_ratio: 1.0,
            view_id: 0,
            ..Default::default()
        };
        check(
            unsafe { FlutterEngineSendWindowMetricsEvent(engine, &metrics) },
            "FlutterEngineSendWindowMetricsEvent",
        )?;
        let (language, country) = (c"en", c"US");
        let locale = FlutterLocale {
            struct_size: size_of::<FlutterLocale>(),
            language_code: language.as_ptr(),
            country_code: country.as_ptr(),
            ..Default::default()
        };
        let locales = [&locale as *const FlutterLocale];
        check(
            unsafe { FlutterEngineUpdateLocales(engine, locales.as_ptr() as *mut _, 1) },
            "FlutterEngineUpdateLocales",
        )?;
        host.send(
            "flutter/settings",
            br#"{"textScaleFactor":1.0,"alwaysUse24HourFormat":false,"platformBrightness":"light"}"#,
        )?;
        host.send("flutter/lifecycle", b"AppLifecycleState.resumed")?;
        // Don't blink text cursors: blinking keeps the app from settling.
        check(
            unsafe {
                FlutterEngineUpdateAccessibilityFeatures(
                    engine,
                    kFlutterAccessibilityFeatureDeterministicCursor,
                )
            },
            "FlutterEngineUpdateAccessibilityFeatures",
        )?;
        check(
            unsafe { FlutterEngineUpdateSemanticsEnabled(engine, true) },
            "FlutterEngineUpdateSemanticsEnabled",
        )?;
        Ok(host)
    }

    fn engine(&self) -> FlutterEngine {
        self.engine.load(Ordering::SeqCst)
    }

    fn queue(&self) -> MutexGuard<'_, Queue> {
        self.queue.lock().unwrap()
    }

    /// What the app has told the driver so far. Don't hold it across calls
    /// into the engine.
    pub fn app(&self) -> MutexGuard<'_, App> {
        self.app.lock().unwrap()
    }

    /// Sends a platform message to the framework, ignoring any reply.
    pub fn send(&self, channel: &str, message: &[u8]) -> Result<(), String> {
        let channel_c = CString::new(channel).unwrap();
        let message = FlutterPlatformMessage {
            struct_size: size_of::<FlutterPlatformMessage>(),
            channel: channel_c.as_ptr(),
            message: message.as_ptr(),
            message_size: message.len(),
            response_handle: ptr::null(),
        };
        check(
            unsafe { FlutterEngineSendPlatformMessage(self.engine(), &message) },
            &format!("sending a platform message on {channel}"),
        )
    }

    pub fn dispatch_semantics_action(
        &self,
        node: i32,
        action: u64,
        data: &[u8],
    ) -> Result<(), String> {
        check(
            unsafe {
                FlutterEngineDispatchSemanticsAction(
                    self.engine(),
                    node as u64,
                    action as FlutterSemanticsAction,
                    if data.is_empty() {
                        ptr::null()
                    } else {
                        data.as_ptr()
                    },
                    data.len(),
                )
            },
            "FlutterEngineDispatchSemanticsAction",
        )
    }

    /// Sends a touch pointer event at `(x, y)`, in logical pixels.
    pub fn pointer(&self, phase: FlutterPointerPhase, x: f64, y: f64) -> Result<(), String> {
        let event = FlutterPointerEvent {
            struct_size: size_of::<FlutterPointerEvent>(),
            phase,
            timestamp: (now() / 1000) as usize,
            x,
            y,
            device: 1,
            device_kind: kFlutterPointerDeviceKindTouch,
            view_id: 0,
            ..Default::default()
        };
        check(
            unsafe { FlutterEngineSendPointerEvent(self.engine(), &event, 1) },
            "FlutterEngineSendPointerEvent",
        )
    }

    /// Takes a line that arrived on stdin, if any. With `None`, stdin is
    /// closed and no lines are left.
    pub fn next_line(&self) -> Option<Option<String>> {
        let mut queue = self.queue();
        match queue.lines.pop_front() {
            Some(line) => Some(Some(line)),
            None if queue.input_closed => Some(None),
            None => None,
        }
    }

    /// Hands over a line from stdin, or `None` once stdin is closed. Call
    /// from any thread.
    pub fn push_line(&self, line: Option<String>) {
        let mut queue = self.queue();
        match line {
            Some(line) => queue.lines.push_back(line),
            None => queue.input_closed = true,
        }
        self.wake.notify_all();
    }

    /// Runs the tasks and vsyncs that are due, then waits until more are,
    /// `deadline`, or, with `for_input`, a line arrives on stdin.
    pub fn pump(&self, deadline: u64, for_input: bool) {
        let engine = self.engine();
        let mut queue = self.queue();
        loop {
            let now = now();
            let mut due = Vec::new();
            while queue.tasks.peek().is_some_and(|t| t.0.target <= now) {
                due.push(queue.tasks.pop().unwrap().0);
            }
            let vsync_at = queue.last_vsync + FRAME_NANOS;
            let batons = if now >= vsync_at && !queue.vsync_batons.is_empty() {
                queue.last_vsync = now;
                std::mem::take(&mut queue.vsync_batons)
            } else {
                Vec::new()
            };
            if !due.is_empty() || !batons.is_empty() {
                drop(queue);
                for task in due {
                    unsafe { FlutterEngineRunTask(engine, &task.task) };
                }
                for baton in batons {
                    unsafe { FlutterEngineOnVsync(engine, baton, now, now + FRAME_NANOS) };
                }
                return;
            }
            if now >= deadline || (for_input && (!queue.lines.is_empty() || queue.input_closed)) {
                return;
            }
            let mut wake = deadline;
            if let Some(task) = queue.tasks.peek() {
                wake = wake.min(task.0.target);
            }
            if !queue.vsync_batons.is_empty() {
                wake = wake.min(vsync_at);
            }
            queue = self
                .wake
                .wait_timeout(queue, Duration::from_nanos(wake.saturating_sub(now)))
                .unwrap()
                .0;
        }
    }

    /// Runs the app until it has asked for no frame and changed no
    /// semantics for `quiet` nanoseconds, and has a semantics tree, or for
    /// at most `cap` nanoseconds. Returns whether it settled.
    ///
    /// Waits for `quiet` at least: the app handles actions and messages
    /// asynchronously, so it may not have reacted to the last one yet.
    pub fn settle(&self, quiet: u64, cap: u64) -> bool {
        let start = now();
        self.active();
        loop {
            let now = now();
            let (last_activity, frame_pending) = {
                let queue = self.queue();
                (queue.last_activity, !queue.vsync_batons.is_empty())
            };
            if now >= last_activity + quiet && !frame_pending && self.app().tree.has_root() {
                return true;
            }
            if now >= start + cap {
                return false;
            }
            self.pump_through(
                (last_activity + quiet)
                    .max(now + millis(10))
                    .min(start + cap),
            );
        }
    }

    /// Runs the app until `deadline`, ignoring stdin.
    pub fn pump_through(&self, deadline: u64) {
        while now() < deadline {
            self.pump(deadline, false);
        }
    }

    fn active(&self) {
        self.queue().last_activity = now();
    }

    pub fn shutdown(&self) {
        unsafe { FlutterEngineShutdown(self.engine()) };
    }
}

fn host<'a>(user_data: *mut c_void) -> &'a Host {
    unsafe { &*(user_data as *const Host) }
}

extern "C" fn present(_: *mut c_void, _: *const c_void, _: usize, _: usize) -> bool {
    true
}

extern "C" fn runs_on_platform_thread(user_data: *mut c_void) -> bool {
    thread::current().id() == host(user_data).platform_thread
}

extern "C" fn post_task(task: FlutterTask, target: u64, user_data: *mut c_void) {
    let host = host(user_data);
    let mut queue = host.queue();
    queue.seq += 1;
    let seq = queue.seq;
    queue.tasks.push(Reverse(Task { target, seq, task }));
    host.wake.notify_all();
}

extern "C" fn vsync(user_data: *mut c_void, baton: isize) {
    let host = host(user_data);
    let mut queue = host.queue();
    queue.vsync_batons.push(baton);
    queue.last_activity = now();
    host.wake.notify_all();
}

extern "C" fn log_message(tag: *const c_char, message: *const c_char, _: *mut c_void) {
    eprintln!("{}: {}", lossy(tag), lossy(message));
}

extern "C" fn platform_message(message: *const FlutterPlatformMessage, user_data: *mut c_void) {
    let host = host(user_data);
    let message = unsafe { &*message };
    let channel = lossy(message.channel);
    let bytes = if message.message.is_null() {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(message.message, message.message_size) }
    };
    let reply = {
        let mut app = host.app();
        if app.channels_seen.insert(channel.clone()) {
            eprintln!("bombadil-flutter-driver: first message on {channel}");
        }
        app.platform.handle(&channel, bytes)
    };
    if !message.response_handle.is_null() {
        unsafe {
            FlutterEngineSendPlatformMessageResponse(
                host.engine(),
                message.response_handle,
                if reply.is_empty() {
                    ptr::null()
                } else {
                    reply.as_ptr()
                },
                reply.len(),
            )
        };
    }
}

fn transform(t: &FlutterTransformation) -> semantics::Transform {
    [
        t.scaleX, t.skewX, t.transX, t.skewY, t.scaleY, t.transY, t.pers0, t.pers1, t.pers2,
    ]
}

fn tristate(value: FlutterTristate, yes: &'static str, no: &'static str) -> Option<&'static str> {
    match value {
        kFlutterTristateTrue => Some(yes),
        kFlutterTristateFalse => Some(no),
        _ => None,
    }
}

fn flags(flags: &FlutterSemanticsFlags) -> Vec<&'static str> {
    let mut out: Vec<&'static str> = [
        match flags.is_checked {
            kFlutterCheckStateTrue => Some("checked"),
            kFlutterCheckStateFalse => Some("unchecked"),
            kFlutterCheckStateMixed => Some("mixed"),
            _ => None,
        },
        tristate(flags.is_selected, "selected", "unselected"),
        tristate(flags.is_enabled, "enabled", "disabled"),
        tristate(flags.is_toggled, "toggled", "untoggled"),
        tristate(flags.is_expanded, "expanded", "collapsed"),
        tristate(flags.is_required, "required", "optional"),
        tristate(flags.is_focused, "focused", "focusable"),
    ]
    .into_iter()
    .flatten()
    .collect();
    for (set, name) in [
        (flags.is_button, "button"),
        (flags.is_text_field, "textField"),
        (
            flags.is_in_mutually_exclusive_group,
            "inMutuallyExclusiveGroup",
        ),
        (flags.is_header, "header"),
        (flags.is_obscured, "obscured"),
        (flags.scopes_route, "scopesRoute"),
        (flags.names_route, "namesRoute"),
        (flags.is_hidden, "hidden"),
        (flags.is_image, "image"),
        (flags.is_live_region, "liveRegion"),
        (flags.has_implicit_scrolling, "implicitScrolling"),
        (flags.is_multiline, "multiline"),
        (flags.is_read_only, "readOnly"),
        (flags.is_link, "link"),
        (flags.is_slider, "slider"),
        (flags.is_keyboard_key, "keyboardKey"),
    ] {
        if set {
            out.push(name);
        }
    }
    out
}

/// Flags from the deprecated bit set, for engines that don't send
/// `flags2`.
fn deprecated_flags(bits: u32) -> Vec<&'static str> {
    let has = |bit: u32| bits & bit != 0;
    let pick =
        |has_state: u32, set: u32, yes, no| has(has_state).then(|| if has(set) { yes } else { no });
    let mut out: Vec<&'static str> = [
        pick(
            kFlutterSemanticsFlagHasCheckedState,
            kFlutterSemanticsFlagIsChecked,
            "checked",
            "unchecked",
        ),
        pick(
            kFlutterSemanticsFlagHasSelectedState,
            kFlutterSemanticsFlagIsSelected,
            "selected",
            "unselected",
        ),
        pick(
            kFlutterSemanticsFlagHasEnabledState,
            kFlutterSemanticsFlagIsEnabled,
            "enabled",
            "disabled",
        ),
        pick(
            kFlutterSemanticsFlagHasToggledState,
            kFlutterSemanticsFlagIsToggled,
            "toggled",
            "untoggled",
        ),
        pick(
            kFlutterSemanticsFlagHasExpandedState,
            kFlutterSemanticsFlagIsExpanded,
            "expanded",
            "collapsed",
        ),
        pick(
            kFlutterSemanticsFlagIsFocusable,
            kFlutterSemanticsFlagIsFocused,
            "focused",
            "focusable",
        ),
    ]
    .into_iter()
    .flatten()
    .collect();
    for (bit, name) in [
        (kFlutterSemanticsFlagIsButton, "button"),
        (kFlutterSemanticsFlagIsTextField, "textField"),
        (kFlutterSemanticsFlagIsHeader, "header"),
        (kFlutterSemanticsFlagIsObscured, "obscured"),
        (kFlutterSemanticsFlagScopesRoute, "scopesRoute"),
        (kFlutterSemanticsFlagNamesRoute, "namesRoute"),
        (kFlutterSemanticsFlagIsHidden, "hidden"),
        (kFlutterSemanticsFlagIsImage, "image"),
        (kFlutterSemanticsFlagIsReadOnly, "readOnly"),
        (kFlutterSemanticsFlagIsLink, "link"),
        (kFlutterSemanticsFlagIsSlider, "slider"),
    ] {
        if has(bit) {
            out.push(name);
        }
    }
    out
}

unsafe fn slice<'a, T>(items: *const T, count: usize) -> &'a [T] {
    if items.is_null() || count == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(items, count)
    }
}

extern "C" fn update_semantics(update: *const FlutterSemanticsUpdate2, user_data: *mut c_void) {
    let host = host(user_data);
    let update = unsafe { &*update };
    let nodes = unsafe { slice(update.nodes, update.node_count) }
        .iter()
        .map(|&node| {
            let node = unsafe { &*node };
            let is_scroller = node.actions
                & (kFlutterSemanticsActionScrollUp
                    | kFlutterSemanticsActionScrollDown
                    | kFlutterSemanticsActionScrollLeft
                    | kFlutterSemanticsActionScrollRight)
                != 0;
            Node {
                id: node.id,
                label: lossy(node.label),
                value: lossy(node.value),
                hint: lossy(node.hint),
                tooltip: lossy(node.tooltip),
                identifier: lossy(node.identifier),
                flags: if node.flags2.is_null() {
                    deprecated_flags(node.flags__deprecated__)
                } else {
                    flags(unsafe { &*node.flags2 })
                },
                actions: node.actions as u64,
                rect: [
                    node.rect.left,
                    node.rect.top,
                    node.rect.right,
                    node.rect.bottom,
                ],
                transform: transform(&node.transform),
                children: unsafe { slice(node.children_in_traversal_order, node.child_count) }
                    .to_vec(),
                scroll: (is_scroller && node.scroll_extent_max.is_finite()).then_some([
                    node.scroll_position,
                    node.scroll_extent_min,
                    node.scroll_extent_max,
                ]),
                custom_actions: unsafe {
                    slice(
                        node.custom_accessibility_actions,
                        node.custom_accessibility_actions_count,
                    )
                }
                .to_vec(),
            }
        })
        .collect();
    let custom_actions = unsafe { slice(update.custom_actions, update.custom_action_count) }
        .iter()
        .map(|&action| {
            let action = unsafe { &*action };
            (action.id, lossy(action.label))
        })
        .collect();
    host.app().tree.update(nodes, custom_actions);
    host.active();
}
