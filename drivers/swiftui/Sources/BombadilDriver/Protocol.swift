/// Version of Bombadil's external driver protocol this package speaks.
public let protocolVersion = 0

/// Messages the driver sends to Bombadil.
public enum DriverMessage: Equatable, Encodable {
    case hello(name: String)
    case state(JSONValue)
    case error(message: String)

    private enum CodingKeys: String, CodingKey {
        case type, `protocol`, name, state, message
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .hello(let name):
            try container.encode("hello", forKey: .type)
            try container.encode(protocolVersion, forKey: .protocol)
            try container.encode(name, forKey: .name)
        case .state(let state):
            try container.encode("state", forKey: .type)
            try container.encode(state, forKey: .state)
        case .error(let message):
            try container.encode("error", forKey: .type)
            try container.encode(message, forKey: .message)
        }
    }
}

/// Messages Bombadil sends to the driver.
public enum BombadilMessage: Equatable, Decodable {
    case apply(action: JSONValue)
    case terminate

    private enum CodingKeys: String, CodingKey {
        case type, action
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        switch try container.decode(String.self, forKey: .type) {
        case "apply":
            self = .apply(action: try container.decode(JSONValue.self, forKey: .action))
        case "terminate":
            self = .terminate
        case let type:
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: container,
                debugDescription: "unknown message type `\(type)`")
        }
    }
}
