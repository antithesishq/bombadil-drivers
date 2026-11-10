// A driver for a pretend counter app, with a bug: nothing caps the
// count. Run it under Bombadil with:
//
//   swift build
//   bombadil external test --specification Examples/counter.spec.ts \
//     -- .build/debug/CounterExample
import BombadilDriver

struct UnknownAction: Error {
    let action: JSONValue
}

final class CounterDriver: BombadilDriver {
    var count = 0

    let name = "counter (swift)"

    var state: JSONValue { ["count": .int(count)] }

    func start() throws -> JSONValue { state }

    func apply(_ action: JSONValue) throws -> JSONValue {
        switch action {
        case .object(let fields):
            guard case .object(let increment)? = fields["Increment"],
                case .int(let by)? = increment["by"]
            else { throw UnknownAction(action: action) }
            count += by
        case "Reset":
            count = 0
        default:
            throw UnknownAction(action: action)
        }
        return state
    }

    func stop() {}
}

try runDriver(CounterDriver())
