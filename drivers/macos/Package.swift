// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BombadilMacOS",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "BombadilMacOS", targets: ["BombadilMacOS"]),
        .executable(name: "bombadil-macos", targets: ["MacOSDriverCLI"]),
    ],
    dependencies: [.package(name: "BombadilDriver", path: "../../lib/swift")],
    targets: [
        .target(name: "BombadilMacOS", dependencies: [.product(name: "BombadilDriver", package: "BombadilDriver")]),
        .executableTarget(name: "MacOSDriverCLI", dependencies: ["BombadilMacOS", .product(name: "BombadilDriver", package: "BombadilDriver")]),
        .testTarget(name: "BombadilMacOSTests", dependencies: ["BombadilMacOS", .product(name: "BombadilDriver", package: "BombadilDriver")]),
    ]
)
