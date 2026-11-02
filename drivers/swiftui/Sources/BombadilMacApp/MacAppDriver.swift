#if os(macOS)
import AppKit
import ApplicationServices
import BombadilDriver
import Foundation
import ScreenCaptureKit

public struct MacAppDriverError: Error, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// How `MacAppDriver` launches and observes an app.
public struct MacAppConfiguration {
    /// Executable to launch: the app's `Contents/MacOS/...` binary, or a
    /// wrapper such as `/usr/bin/sandbox-exec` that execs it in place, so
    /// that the launched process is the app.
    public var executable: String
    public var arguments: [String]
    /// Added to the driver's own environment.
    public var environment: [String: String]
    /// Where the app's stdout and stderr go; the driver's stderr if nil.
    public var appLogPath: String?
    /// How long to wait for the app to show a window or a menu bar extra.
    public var launchTimeout: TimeInterval = 30
    /// The state is settled once two reads `settleInterval` apart are
    /// equal and responsive. A timeout reports `settled: false`.
    public var settleTimeout: TimeInterval = 3
    public var settleInterval: TimeInterval = 0.15
    /// Bounds on the size of the reported tree.
    public var maxNodes = 3000
    public var maxDepth = 40
    /// Report the app's main menu bar (`menuBar`) too.
    public var includeMainMenu = false
    /// Announced to Bombadil; launching and settling can take a while.
    public var stallTimeoutMs = 90_000

    public init(executable: String, arguments: [String] = [], environment: [String: String] = [:]) {
        self.executable = executable
        self.arguments = arguments
        self.environment = environment
    }
}

/// A `BombadilDriver` for any macOS app, built on the Accessibility API.
///
/// It launches the app, and reports as its state the accessibility trees
/// of the app's menu bar extras (`statusItems`, with any open menu below
/// its item), its windows, popovers and other top-level elements
/// (`windows`), and optionally its main menu (`menuBar`):
///
/// ```json
/// {"running": true, "pid": 123, "actionError": null, "truncated": false,
///  "statusItems": [<node>], "windows": [<node>], "menuBar": []}
/// ```
///
/// where a node is
///
/// ```json
/// {"path": "windows/0/2", "role": "AXButton", "subrole": null,
///  "title": "OK", "value": null, "identifier": null, "description": null,
///  "enabled": true, "focused": false, "selected": null, "checked": null,
///  "frame": {"x": 0, "y": 0, "width": 80, "height": 24}, "children": []}
/// ```
///
/// It performs `MacAppAction`s. Actions that fail (an element that is
/// gone, an AX error) don't end the run: the next state has
/// `actionError` set instead.
///
/// The process that calls the Accessibility API needs the Accessibility
/// permission, which macOS only grants to processes in the user's GUI
/// session, so run this driver from a terminal in the GUI session or as a
/// LaunchAgent (see `scripts/run-in-gui-session.sh`).
public final class MacAppDriver: BombadilDriver {
    public let name = "bombadil-macapp-driver"
    public var stallTimeoutMs: Int? { configuration.stallTimeoutMs }
    public private(set) var stateMetadata = StateMetadata()

    let configuration: MacAppConfiguration
    var process: Process?
    var app: AXUIElement?
    var actionError: String?
    var screenshotDiagnostic: String?
    /// After a mutating API is called, failures may have partially applied the action.
    var actionAttempted = false

    /// The launched app's process ID, for signal handlers that need to
    /// stop it when the driver is killed.
    public private(set) static var launchedPID: pid_t = 0

    public init(_ configuration: MacAppConfiguration) {
        self.configuration = configuration
    }

    // MARK: Session

    public func start() throws -> JSONValue {
        stateMetadata = StateMetadata()
        actionError = nil
        try Self.requireAccessibility()
        // A menu that is open blocks AXPress on its status item until it
        // closes, so keep messages short and treat timeouts as success.
        AXUIElementSetMessagingTimeout(AXUIElementCreateSystemWide(), 1.0)

        let process = Process()
        process.executableURL = URL(fileURLWithPath: configuration.executable)
        process.arguments = configuration.arguments
        process.environment = ProcessInfo.processInfo.environment.merging(configuration.environment) { $1 }
        let appLog: FileHandle
        if let path = configuration.appLogPath {
            FileManager.default.createFile(atPath: path, contents: nil)
            guard let handle = FileHandle(forWritingAtPath: path) else {
                throw MacAppDriverError("can't write the app log \(path)")
            }
            appLog = handle
        } else {
            appLog = FileHandle.standardError
        }
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = appLog
        process.standardError = appLog
        try process.run()
        self.process = process
        Self.launchedPID = process.processIdentifier
        let app = AXUIElementCreateApplication(process.processIdentifier)
        self.app = app
        log("launched \(configuration.executable) as pid \(process.processIdentifier)")

        let deadline = Date().addingTimeInterval(configuration.launchTimeout)
        while true {
            guard process.isRunning else {
                throw MacAppDriverError(
                    "the app exited with status \(process.terminationStatus) while launching")
            }
            if !statusItemElements(app).isEmpty || !topLevelElements(app).isEmpty { break }
            guard Date() < deadline else {
                throw MacAppDriverError(
                    "the app showed no window or menu bar extra within \(Int(configuration.launchTimeout))s")
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return settledState()
    }

    public func apply(_ actionJSON: JSONValue) throws -> JSONValue {
        actionError = nil
        actionAttempted = false
        stateMetadata = StateMetadata()
        do {
            guard process?.isRunning == true else { throw MacAppDriverError("the app is not running") }
            try perform(try MacAppAction(json: actionJSON))
        } catch {
            actionError = "\(error)"
            if !actionAttempted { stateMetadata.notApplied = actionError }
            log("action \(actionJSON) failed: \(error)")
        }
        return settledState()
    }

    public func stop() {
        guard let process, process.isRunning else { return }
        process.terminate()
        let deadline = Date().addingTimeInterval(3)
        while process.isRunning && Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
        if process.isRunning { kill(process.processIdentifier, SIGKILL) }
        process.waitUntilExit()
        log("stopped the app")
    }

    /// Throws, and asks macOS to prompt the user, if this process may not
    /// use the Accessibility API.
    public static func requireAccessibility() throws {
        if AXIsProcessTrusted() { return }
        let prompt = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([prompt: true] as CFDictionary)
        let path = Bundle.main.executableURL?.resolvingSymlinksInPath().path ?? CommandLine.arguments[0]
        throw MacAppDriverError("""
            this process may not use the Accessibility API. Allow \(path) in System Settings > \
            Privacy & Security > Accessibility, and run it in the user's GUI session (not straight \
            from ssh; see scripts/run-in-gui-session.sh)
            """)
    }

    // MARK: State

    struct Root {
        let name: String
        let elements: [AXUIElement]
    }

    func roots() -> [Root] {
        guard let app else { return [] }
        var roots = [
            Root(name: "statusItems", elements: statusItemElements(app)),
            Root(name: "windows", elements: topLevelElements(app)),
        ]
        if configuration.includeMainMenu {
            let menuBar: AXUIElement? = attribute(app, kAXMenuBarAttribute)
            roots.append(Root(name: "menuBar", elements: menuBar.map { children($0) } ?? []))
        }
        return roots
    }

    func statusItemElements(_ app: AXUIElement) -> [AXUIElement] {
        let extras: AXUIElement? = attribute(app, "AXExtrasMenuBar")
        return extras.map { children($0) } ?? []
    }

    /// The app's windows and other top-level elements, such as menus it
    /// opened outside a menu bar.
    func topLevelElements(_ app: AXUIElement) -> [AXUIElement] {
        var elements: [AXUIElement] = []
        let windows: [AXUIElement] = attribute(app, kAXWindowsAttribute) ?? []
        for element in windows + children(app) {
            let role: String? = attribute(element, kAXRoleAttribute)
            if role == kAXMenuBarRole as String { continue }
            if elements.contains(where: { CFEqual($0, element) }) { continue }
            elements.append(element)
        }
        return elements
    }

    /// Set when a read timed out during `readState`, which then skips the
    /// rest of the tree.
    var unresponsive = false

    func readState() -> JSONValue {
        var budget = configuration.maxNodes
        unresponsive = false
        var state: [String: JSONValue] = [
            "running": .bool(process?.isRunning ?? false),
            "pid": .int(Int(process?.processIdentifier ?? 0)),
            "actionError": actionError.map { .string($0) } ?? .null,
            "statusItems": [],
            "windows": [],
            "menuBar": [],
        ]
        if process?.isRunning ?? false {
            for root in roots() where !unresponsive {
                state[root.name] = .array(root.elements.enumerated().map { index, element in
                    node(element, path: "\(root.name)/\(index)", depth: 0, budget: &budget)
                })
            }
        }
        state["truncated"] = .bool(budget <= 0)
        return .object(state)
    }

    func settledState() -> JSONValue {
        var previous = readState()
        var previousResponsive = !unresponsive
        let deadline = Date().addingTimeInterval(configuration.settleTimeout)
        while Date() < deadline {
            Thread.sleep(forTimeInterval: configuration.settleInterval)
            let current = readState()
            if current == previous && previousResponsive && !unresponsive {
                stateMetadata.settled = true
                return current
            }
            previous = current
            previousResponsive = !unresponsive
        }
        stateMetadata.settled = false
        log("state didn't settle within \(configuration.settleTimeout)s")
        return previous
    }

    static let nodeAttributes = [
        kAXRoleAttribute, kAXSubroleAttribute, kAXTitleAttribute, kAXValueAttribute,
        kAXIdentifierAttribute, kAXDescriptionAttribute, kAXEnabledAttribute, kAXFocusedAttribute,
        kAXSelectedAttribute, kAXMenuItemMarkCharAttribute, kAXPositionAttribute, kAXSizeAttribute,
        kAXChildrenAttribute,
    ] as [String]

    func node(_ element: AXUIElement, path: String, depth: Int, budget: inout Int) -> JSONValue {
        budget -= 1
        var values: CFArray?
        if !unresponsive,
            AXUIElementCopyMultipleAttributeValues(
                element, Self.nodeAttributes as CFArray, AXCopyMultipleAttributeOptions(rawValue: 0), &values)
                == .cannotComplete
        {
            unresponsive = true
        }
        let raw = (values as [AnyObject]?) ?? []
        func value(_ name: String) -> AnyObject? {
            guard let index = Self.nodeAttributes.firstIndex(of: name), index < raw.count else { return nil }
            let value = raw[index]
            if CFGetTypeID(value) == AXValueGetTypeID(),
                AXValueGetType(value as! AXValue) == .axError
            {
                return nil
            }
            return value
        }
        func string(_ name: String) -> JSONValue {
            switch value(name) {
            case let string as String: return .string(string)
            case let string as NSAttributedString: return .string(string.string)
            default: return .null
            }
        }
        func bool(_ name: String) -> JSONValue {
            (value(name) as? Bool).map { .bool($0) } ?? .null
        }

        var frame: JSONValue = .null
        if let position = value(kAXPositionAttribute).flatMap(point),
            let size = value(kAXSizeAttribute).flatMap(size)
        {
            frame = [
                "x": .int(Int(position.x.rounded())), "y": .int(Int(position.y.rounded())),
                "width": .int(Int(size.width.rounded())), "height": .int(Int(size.height.rounded())),
            ]
        }
        let mark = string(kAXMenuItemMarkCharAttribute)
        var children: [JSONValue] = []
        if depth < configuration.maxDepth, let elements = value(kAXChildrenAttribute) as? [AXUIElement] {
            for (index, child) in elements.enumerated() where budget > 0 {
                children.append(node(child, path: "\(path)/\(index)", depth: depth + 1, budget: &budget))
            }
        }
        return [
            "path": .string(path),
            "role": string(kAXRoleAttribute),
            "subrole": string(kAXSubroleAttribute),
            "title": string(kAXTitleAttribute),
            "value": json(value(kAXValueAttribute)),
            "identifier": string(kAXIdentifierAttribute),
            "description": string(kAXDescriptionAttribute),
            "enabled": bool(kAXEnabledAttribute),
            "focused": bool(kAXFocusedAttribute),
            "selected": bool(kAXSelectedAttribute),
            "checked": mark == .null ? .null : .bool(mark != ""),
            "frame": frame,
            "children": .array(children),
        ]
    }

    func json(_ value: AnyObject?) -> JSONValue {
        switch value {
        case let string as String: return .string(string)
        case let string as NSAttributedString: return .string(string.string)
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return .bool(number.boolValue) }
            if CFNumberIsFloatType(number) {
                let double = number.doubleValue
                return double.isFinite ? .double(double) : .null
            }
            return .int(number.intValue)
        case let url as URL: return .string(url.absoluteString)
        default: return .null
        }
    }

    // MARK: Actions

    func perform(_ action: MacAppAction) throws {
        switch action {
        case .press(let query):
            try press(try find(query))
        case .showMenu(let query):
            let element = try find(query)
            actionAttempted = true
            try check(AXUIElementPerformAction(element, kAXShowMenuAction as CFString), "AXShowMenu")
        case .openStatusItem(let index):
            let items = app.map(statusItemElements) ?? []
            guard items.indices.contains(index) else {
                throw MacAppDriverError("the app has \(items.count) menu bar extras, not \(index + 1)")
            }
            try press(items[index])
        case .typeText(let text, let query):
            actionAttempted = true
            activate()
            if let query {
                let element = try find(query)
                AXUIElementSetAttributeValue(element, kAXFocusedAttribute as CFString, kCFBooleanTrue)
            }
            try type(text)
        case .key(let name, let modifiers):
            guard let code = Self.keyCodes[name.lowercased()] else {
                throw MacAppDriverError("unknown key `\(name)`; known keys: \(Self.keyCodes.keys.sorted())")
            }
            var flags: CGEventFlags = []
            for modifier in modifiers {
                guard let flag = Self.modifierFlags[modifier.lowercased()] else {
                    throw MacAppDriverError("unknown modifier `\(modifier)`")
                }
                flags.insert(flag)
            }
            try postKey(code, flags: flags)
        case .escape:
            if let menu = openMenu() {
                actionAttempted = true
                try check(AXUIElementPerformAction(menu, kAXCancelAction as CFString), "AXCancel")
            } else {
                try postKey(Self.keyCodes["escape"]!, flags: [])
            }
        case .closeWindow(let query):
            let element = try find(query)
            let role: String? = attribute(element, kAXRoleAttribute)
            let window = role == kAXWindowRole as String ? element : attribute(element, kAXWindowAttribute)
            guard let window else { throw MacAppDriverError("the element isn't in a window") }
            guard let close: AXUIElement = attribute(window, kAXCloseButtonAttribute) else {
                throw MacAppDriverError("the window has no close button")
            }
            try press(close)
        case .wait(let milliseconds):
            Thread.sleep(forTimeInterval: Double(milliseconds) / 1000)
        }
    }

    func press(_ element: AXUIElement) throws {
        var names: CFArray?
        AXUIElementCopyActionNames(element, &names)
        let actions = (names as? [String]) ?? []
        guard let action = [kAXPressAction, kAXPickAction, kAXConfirmAction]
            .map({ $0 as String }).first(where: actions.contains)
        else {
            throw MacAppDriverError("the element has no press action, only \(actions)")
        }
        actionAttempted = true
        try check(AXUIElementPerformAction(element, action as CFString), action)
    }

    func check(_ error: AXError, _ what: String) throws {
        switch error {
        // Opening a menu blocks the app until the menu closes, so the
        // action times out although it was performed.
        case .success, .cannotComplete: return
        default: throw MacAppDriverError("\(what) failed with AXError \(error.rawValue)")
        }
    }

    /// The element `query` picks out of the current tree.
    func find(_ query: ElementQuery) throws -> AXUIElement {
        let roots = roots()
        if let path = query.path {
            guard let (rootName, indices) = parseElementPath(path),
                let root = roots.first(where: { $0.name == rootName })
            else { throw MacAppDriverError("invalid path `\(path)`") }
            var element: AXUIElement? = root.elements.indices.contains(indices[0]) ? root.elements[indices[0]] : nil
            for index in indices.dropFirst() {
                guard let parent = element else { break }
                let children = children(parent)
                element = children.indices.contains(index) ? children[index] : nil
            }
            if let element, matches(element, query) { return element }
            guard query.hasAttributes else { throw MacAppDriverError("no element at `\(path)`") }
        }
        var budget = configuration.maxNodes
        func search(_ element: AXUIElement, depth: Int) -> AXUIElement? {
            budget -= 1
            if matches(element, query) { return element }
            guard depth < configuration.maxDepth else { return nil }
            for child in children(element) where budget > 0 {
                if let found = search(child, depth: depth + 1) { return found }
            }
            return nil
        }
        for root in roots {
            for element in root.elements {
                if let found = search(element, depth: 0) { return found }
            }
        }
        throw MacAppDriverError("no element matches \(query)")
    }

    func matches(_ element: AXUIElement, _ query: ElementQuery) -> Bool {
        query.matches(
            role: attribute(element, kAXRoleAttribute), title: attribute(element, kAXTitleAttribute),
            identifier: attribute(element, kAXIdentifierAttribute))
    }

    /// The first open menu, if any.
    func openMenu() -> AXUIElement? {
        var budget = configuration.maxNodes
        func search(_ element: AXUIElement, depth: Int) -> AXUIElement? {
            budget -= 1
            let role: String? = attribute(element, kAXRoleAttribute)
            if role == kAXMenuRole as String { return element }
            guard depth < configuration.maxDepth else { return nil }
            for child in children(element) where budget > 0 {
                if let found = search(child, depth: depth + 1) { return found }
            }
            return nil
        }
        for root in roots() {
            for element in root.elements {
                if let found = search(element, depth: 0) { return found }
            }
        }
        return nil
    }

    func activate() {
        guard let process else { return }
        NSRunningApplication(processIdentifier: process.processIdentifier)?.activate()
    }

    func type(_ text: String) throws {
        guard let pid = process?.processIdentifier else { return }
        for character in text {
            let units = Array(String(character).utf16)
            for keyDown in [true, false] {
                guard let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: keyDown) else {
                    throw MacAppDriverError("can't create a keyboard event")
                }
                event.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
                actionAttempted = true
                event.postToPid(pid)
            }
            Thread.sleep(forTimeInterval: 0.01)
        }
    }

    func postKey(_ code: CGKeyCode, flags: CGEventFlags) throws {
        guard let pid = process?.processIdentifier else { return }
        for keyDown in [true, false] {
            guard let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: keyDown) else {
                throw MacAppDriverError("can't create a keyboard event")
            }
            event.flags = flags
            actionAttempted = true
            event.postToPid(pid)
        }
    }

    static let keyCodes: [String: CGKeyCode] = [
        "return": 0x24, "tab": 0x30, "space": 0x31, "delete": 0x33, "escape": 0x35,
        "forwarddelete": 0x75, "home": 0x73, "end": 0x77, "pageup": 0x74, "pagedown": 0x79,
        "left": 0x7B, "right": 0x7C, "down": 0x7D, "up": 0x7E,
        "a": 0x00, "c": 0x08, "v": 0x09, "w": 0x0D, "q": 0x0C, ",": 0x2B,
    ]

    static let modifierFlags: [String: CGEventFlags] = [
        "command": .maskCommand, "shift": .maskShift, "option": .maskAlternate, "control": .maskControl,
    ]

    // MARK: Screenshots

    /// Capture only the frontmost visible window owned by the launched
    /// process, never a desktop crop (which could expose other apps).
    public func screenshotPNG() -> Data? {
        func omitted(_ reason: String) -> Data? {
            if screenshotDiagnostic != reason {
                log("screenshot_png omitted: \(reason)")
                screenshotDiagnostic = reason
            }
            return nil
        }
        guard #available(macOS 14.0, *) else {
            return omitted("window screenshots require macOS 14 or later")
        }
        guard let process, process.isRunning else {
            return omitted("target app is not running")
        }
        guard CGPreflightScreenCaptureAccess() else {
            return omitted("Screen Recording permission unavailable; allow this signed driver in System Settings > Privacy & Security > Screen Recording, then restart the driver")
        }
        let pid = process.processIdentifier
        // WindowServer returns front-to-back order. Only use metadata for
        // choosing a target-owned window; no screen pixels are captured here.
        let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID)
            as? [[String: Any]] ?? []
        let windowIDs = windows.compactMap { info -> CGWindowID? in
            guard (info[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == pid else { return nil }
            return (info[kCGWindowNumber as String] as? NSNumber)?.uint32Value
        }
        let completed = DispatchSemaphore(value: 0)
        var result: Result<Data, Error>?
        SCShareableContent.getExcludingDesktopWindows(true, onScreenWindowsOnly: true) { content, error in
            guard let content else {
                result = .failure(error ?? MacAppDriverError("no shareable window content"))
                completed.signal()
                return
            }
            let window = windowIDs.lazy.compactMap { id in
                content.windows.first {
                    $0.windowID == id && $0.owningApplication?.processID == pid
                        && $0.frame.width > 1 && $0.frame.height > 1
                }
            }.first
            guard let window else {
                result = .failure(MacAppDriverError("target app has no capturable on-screen window"))
                completed.signal()
                return
            }
            let filter = SCContentFilter(desktopIndependentWindow: window)
            let config = SCStreamConfiguration()
            config.width = Int(window.frame.width.rounded(.up))
            config.height = Int(window.frame.height.rounded(.up))
            config.showsCursor = false
            config.ignoreShadowsSingleWindow = true
            SCScreenshotManager.captureImage(contentFilter: filter, configuration: config) { image, error in
                if let image, let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) {
                    result = .success(png)
                } else {
                    result = .failure(error ?? MacAppDriverError("window capture produced no PNG"))
                }
                completed.signal()
            }
        }
        // The callback owns its result even if it arrives after timeout.
        // Never read it without the semaphore establishing completion.
        guard completed.wait(timeout: .now() + 10) == .success else {
            return omitted("window capture timed out after 10s")
        }
        switch result {
        case .success(let png):
            screenshotDiagnostic = nil
            return png
        case .failure(let error): return omitted("\(error)")
        case nil: return omitted("window capture returned no result")
        }
    }

    // MARK: AX helpers

    func attribute<T>(_ element: AXUIElement, _ name: String) -> T? {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(element, name as CFString, &value)
        if error == .cannotComplete { unresponsive = true }
        guard error == .success else { return nil }
        return value as? T
    }

    func children(_ element: AXUIElement) -> [AXUIElement] {
        attribute(element, kAXChildrenAttribute) ?? []
    }

    func point(_ value: AnyObject) -> CGPoint? {
        guard CFGetTypeID(value) == AXValueGetTypeID() else { return nil }
        var point = CGPoint.zero
        return AXValueGetValue(value as! AXValue, .cgPoint, &point) ? point : nil
    }

    func size(_ value: AnyObject) -> CGSize? {
        guard CFGetTypeID(value) == AXValueGetTypeID() else { return nil }
        var size = CGSize.zero
        return AXValueGetValue(value as! AXValue, .cgSize, &size) ? size : nil
    }

    func log(_ message: String) {
        FileHandle.standardError.write(Data("[\(name)] \(message)\n".utf8))
    }
}
#endif
