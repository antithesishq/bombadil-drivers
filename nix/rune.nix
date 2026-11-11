{ pkgs }:
pkgs.mkShell {
  packages = with pkgs; [
    cargo
    rustc
    rustfmt
    clippy
    pkg-config
  ];
  buildInputs = with pkgs; [
    wayland
    libxkbcommon
    vulkan-loader
    fontconfig
  ];
  LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [
    pkgs.vulkan-loader
    pkgs.fontconfig
    pkgs.wayland
    pkgs.libxkbcommon
  ];
}
