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

      # The prebuilt embedder engine (flutter_embedder.h and a debug/JIT
      # libflutter_engine.so) for the engine revision of nixpkgs' Flutter,
      # from `bin/internal/engine.version` in the Flutter SDK. Update the
      # revision and hash together with nixpkgs.
      engineRevision = "06a2e2a110089dff50fe635cffd2a61e1b24fbcd";
      flutterEmbedder =
        pkgs:
        pkgs.fetchzip {
          name = "flutter-embedder-${engineRevision}";
          url = "https://storage.googleapis.com/flutter_infra_release/flutter/${engineRevision}/linux-x64/linux-x64-embedder.zip";
          hash = "sha256-84P3zZEJ6VJx4KSc0/ehWVBXHUpH1aG7PJR8/tLMFtE=";
          stripRoot = false;
        };

      # Fonts for the driver's `--fonts`, one directory per family: the
      # engine only looks for system fonts in /usr/share/fonts. Roboto is
      # Material's font on Linux, DejaVu Sans the engine's fallback.
      fonts =
        pkgs:
        pkgs.runCommand "bombadil-flutter-fonts" { } ''
          mkdir -p $out/Roboto "$out/DejaVu Sans"
          ln -s ${pkgs.roboto}/share/fonts/truetype/Roboto-*.ttf $out/Roboto/
          ln -s ${pkgs.dejavu_fonts}/share/fonts/truetype/DejaVuSans{,-Bold,-Oblique,-BoldOblique}.ttf "$out/DejaVu Sans/"
        '';
    in
    {
      packages = forAllSystems (
        pkgs:
        {
          fonts = fonts pkgs;
        }
        // nixpkgs.lib.optionalAttrs (pkgs.stdenv.hostPlatform.system == "x86_64-linux") {
          flutter-embedder = flutterEmbedder pkgs;
        }
      );

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell (
          {
            # Flutter brings its own Dart SDK.
            packages = with pkgs; [
              flutter
              git
              cargo
              rustc
              rustfmt
              clippy
              rust-bindgen
              clang
            ];
            # For bindgen.
            LIBCLANG_PATH = "${pkgs.libclang.lib}/lib";
            # For the driver's --fonts.
            BOMBADIL_FLUTTER_FONTS = fonts pkgs;
          }
          // nixpkgs.lib.optionalAttrs (pkgs.stdenv.hostPlatform.system == "x86_64-linux") {
            # Read by embedder/build.rs.
            FLUTTER_EMBEDDER = flutterEmbedder pkgs;
          }
        );
      });
    };
}
