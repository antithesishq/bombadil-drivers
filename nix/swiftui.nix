{ pkgs }:
pkgs.mkShell {
  packages = with pkgs; [
    swift
    swiftpm
  ];
}
