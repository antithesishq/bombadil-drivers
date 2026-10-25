// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BombadilSwiftUI",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "BombadilDriver", targets: ["BombadilDriver"]),
    ],
    targets: [
        .target(name: "BombadilDriver"),
        .executableTarget(
            name: "CounterExample",
            dependencies: ["BombadilDriver"],
            path: "Examples/CounterExample"
        ),
        .testTarget(
            name: "BombadilDriverTests",
            dependencies: ["BombadilDriver"]
        ),
    ]
)
