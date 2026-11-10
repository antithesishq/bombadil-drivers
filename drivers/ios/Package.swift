// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BombadilIOS",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "BombadilIOS", targets: ["BombadilIOS"]),
        .executable(name: "bombadil-ios", targets: ["IOSDriverCLI"]),
    ],
    dependencies: [.package(name: "BombadilDriver", path: "../../lib/swift")],
    targets: [
        .target(name: "BombadilIOS", dependencies: [.product(name: "BombadilDriver", package: "BombadilDriver")]),
        .executableTarget(name: "IOSDriverCLI", dependencies: ["BombadilIOS", .product(name: "BombadilDriver", package: "BombadilDriver")]),
        .testTarget(name: "BombadilIOSTests", dependencies: ["BombadilIOS", .product(name: "BombadilDriver", package: "BombadilDriver")]),
    ]
)
