{
  description = "Bombadil external driver for Flutter apps";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # Fonts for apps that rely on system fonts, one directory per family:
      # the engine only looks for system fonts in /usr/share/fonts, and the
      # driver loads these with FontLoader instead. Roboto is Material's
      # font on Linux, DejaVu Sans the engine's fallback.
      fonts =
        pkgs:
        pkgs.runCommand "bombadil-flutter-fonts" { } ''
          mkdir -p $out/Roboto "$out/DejaVu Sans"
          ln -s ${pkgs.roboto}/share/fonts/truetype/Roboto-*.ttf $out/Roboto/
          ln -s ${pkgs.dejavu_fonts}/share/fonts/truetype/DejaVuSans{,-Bold,-Oblique,-BoldOblique}.ttf "$out/DejaVu Sans/"
        '';
    in
    {
      packages = forAllSystems (pkgs: {
        fonts = fonts pkgs;
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          # Flutter brings its own Dart SDK and flutter_tester.
          packages = with pkgs; [
            flutter
            git
          ];
          # Read by runBombadil.
          BOMBADIL_FLUTTER_FONTS = fonts pkgs;
        };
      });
    };
}
