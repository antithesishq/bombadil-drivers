// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BombadilSwiftUI",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "BombadilDriver", targets: ["BombadilDriver"]),
        // The Accessibility-based driver is macOS-only; elsewhere only its
        // action model builds.
        .library(name: "BombadilMacApp", targets: ["BombadilMacApp"]),
        .executable(name: "bombadil-macapp-driver", targets: ["MacAppDriverCLI"]),
    ],
    targets: [
        .target(name: "BombadilDriver"),
        .target(name: "BombadilMacApp", dependencies: ["BombadilDriver"]),
        .executableTarget(name: "MacAppDriverCLI", dependencies: ["BombadilDriver", "BombadilMacApp"]),
        .executableTarget(
            name: "CounterExample",
            dependencies: ["BombadilDriver"],
            path: "Examples/CounterExample"
        ),
        .testTarget(
            name: "BombadilDriverTests",
            dependencies: ["BombadilDriver"]
        ),
        .testTarget(
            name: "BombadilMacAppTests",
            dependencies: ["BombadilDriver", "BombadilMacApp"]
        ),
    ]
)
