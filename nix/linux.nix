{ pkgs }:
let
  package = pkgs.rustPlatform.buildRustPackage {
    pname = "bombadil-linux";
    version = "0.1.0";
    src = pkgs.lib.fileset.toSource {
      root = ../drivers/linux;
      fileset = pkgs.lib.fileset.unions [
        ../drivers/linux/Cargo.toml
        ../drivers/linux/Cargo.lock
        ../drivers/linux/src
        ../drivers/linux/tests
      ];
    };
    cargoLock.lockFile = ../drivers/linux/Cargo.lock;
    nativeBuildInputs = [ pkgs.makeWrapper ];
    postInstall = ''
      wrapProgram $out/bin/bombadil-linux --prefix PATH : ${
        pkgs.lib.makeBinPath [
          pkgs.hyprland
          pkgs.ydotool
          pkgs.systemd
          pkgs.openssh
        ]
      }
    '';
    meta.platforms = pkgs.lib.platforms.linux;
  };
in
{
  inherit package;
  shell = pkgs.mkShell {
    packages = with pkgs; [
      cargo
      rustc
      rustfmt
      clippy
    ];
  };
}
