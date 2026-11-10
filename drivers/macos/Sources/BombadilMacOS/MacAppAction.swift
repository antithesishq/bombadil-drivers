import BombadilDriver

/// Picks out one accessibility element in the app's state.
///
/// `path` is the element's `path` in the last state. When `role`,
/// `title` or `identifier` are given too, the element at `path` must
/// match them; if it doesn't (the tree moved), the driver presses the
/// first element in the tree that does.
public struct ElementQuery: Equatable {
    public var path: String?
    public var role: String?
    public var title: String?
    public var identifier: String?

    public init(path: String? = nil, role: String? = nil, title: String? = nil, identifier: String? = nil) {
        self.path = path
        self.role = role
        self.title = title
        self.identifier = identifier
    }

    /// Whether an element with these attributes satisfies the query's
    /// `role`, `title` and `identifier`.
    public func matches(role: String?, title: String?, identifier: String?) -> Bool {
        (self.role == nil || self.role == role)
            && (self.title == nil || self.title == title)
            && (self.identifier == nil || self.identifier == identifier)
    }

    var hasAttributes: Bool { role != nil || title != nil || identifier != nil }
}

/// The actions `MacAppDriver` performs, as externally tagged JSON:
///
/// - `{"Press": <query>}`: performs the element's press action (AXPress,
///   or AXPick/AXConfirm for elements without one).
/// - `{"ShowMenu": <query>}`: opens the element's context menu.
/// - `"OpenStatusItem"` or `{"OpenStatusItem": {"index": n}}`: presses
///   the app's `n`th menu bar extra (0 by default).
/// - `{"TypeText": {"text": "...", ...<query>}}`: focuses the element, if
///   a query is given, and types `text` into the app.
/// - `{"Key": {"key": "return", "modifiers": ["command"]}}`: presses a key.
/// - `"Escape"`: cancels the open menu, or presses Escape.
/// - `{"CloseWindow": <query>}`: presses the close button of the window
///   that contains the element.
/// - `{"Wait": {"ms": n}}`: waits up to 10 seconds.
///
/// A query is an object with the fields of `ElementQuery`.
public enum MacAppAction: Equatable {
    case press(ElementQuery)
    case showMenu(ElementQuery)
    case openStatusItem(index: Int)
    case typeText(String, into: ElementQuery?)
    case key(String, modifiers: [String])
    case escape
    case closeWindow(ElementQuery)
    case wait(milliseconds: Int)

    public struct Invalid: Error, CustomStringConvertible {
        public let action: JSONValue
        public let reason: String
        public var description: String { "invalid action \(action): \(reason)" }
    }

    public init(json action: JSONValue) throws {
        let tag: String
        let fields: [String: JSONValue]
        switch action {
        case .string(let name):
            (tag, fields) = (name, [:])
        case .object(let object) where object.count == 1:
            let (name, value) = object.first!
            guard case .object(let object) = value else {
                throw Invalid(action: action, reason: "`\(name)` takes an object")
            }
            (tag, fields) = (name, object)
        default:
            throw Invalid(action: action, reason: "expected a string or an object with one key")
        }

        func string(_ key: String) throws -> String? {
            switch fields[key] {
            case nil, .null?: return nil
            case .string(let value)?: return value
            case let value?: throw Invalid(action: action, reason: "`\(key)` must be a string, not \(value)")
            }
        }
        func int(_ key: String) throws -> Int? {
            switch fields[key] {
            case nil, .null?: return nil
            case .int(let value)?: return value
            case .double(let value)? where value.rounded() == value: return Int(value)
            case let value?: throw Invalid(action: action, reason: "`\(key)` must be an integer, not \(value)")
            }
        }
        func query() throws -> ElementQuery {
            let query = ElementQuery(
                path: try string("path"), role: try string("role"),
                title: try string("title"), identifier: try string("identifier"))
            guard query.path != nil || query.hasAttributes else {
                throw Invalid(action: action, reason: "needs a `path`, `role`, `title` or `identifier`")
            }
            return query
        }

        switch tag {
        case "Press": self = .press(try query())
        case "ShowMenu": self = .showMenu(try query())
        case "OpenStatusItem": self = .openStatusItem(index: try int("index") ?? 0)
        case "TypeText":
            guard let text = try string("text") else {
                throw Invalid(action: action, reason: "`TypeText` needs a `text`")
            }
            let target = fields.keys.contains { $0 != "text" } ? try query() : nil
            self = .typeText(text, into: target)
        case "Key":
            guard let key = try string("key") else {
                throw Invalid(action: action, reason: "`Key` needs a `key`")
            }
            var modifiers: [String] = []
            switch fields["modifiers"] {
            case nil, .null?: break
            case .array(let values)?:
                for value in values {
                    guard case .string(let modifier) = value else {
                        throw Invalid(action: action, reason: "`modifiers` must be strings")
                    }
                    modifiers.append(modifier)
                }
            case let value?: throw Invalid(action: action, reason: "`modifiers` must be an array, not \(value)")
            }
            self = .key(key, modifiers: modifiers)
        case "Escape": self = .escape
        case "CloseWindow": self = .closeWindow(try query())
        case "Wait": self = .wait(milliseconds: min(max(try int("ms") ?? 500, 0), 10_000))
        default: throw Invalid(action: action, reason: "unknown action `\(tag)`")
        }
    }
}

/// Splits a state path such as `"windows/0/3/1"` into its root
/// (`"windows"`) and child indices (`[0, 3, 1]`).
public func parseElementPath(_ path: String) -> (root: String, indices: [Int])? {
    let parts = path.split(separator: "/", omittingEmptySubsequences: false)
    guard let root = parts.first, !root.isEmpty, parts.count > 1 else { return nil }
    var indices: [Int] = []
    for part in parts.dropFirst() {
        guard let index = Int(part), index >= 0 else { return nil }
        indices.append(index)
    }
    return (String(root), indices)
}
