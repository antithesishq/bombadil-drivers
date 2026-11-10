{ pkgs }:
let
  # Fonts for apps that rely on system fonts, one directory per family:
  # the engine only looks for system fonts in /usr/share/fonts, and the
  # driver loads these with FontLoader instead. Roboto is Material's
  # font on Linux, DejaVu Sans the engine's fallback.
  fonts = pkgs.runCommand "bombadil-flutter-fonts" { } ''
    mkdir -p $out/Roboto "$out/DejaVu Sans"
    ln -s ${pkgs.roboto}/share/fonts/truetype/Roboto-*.ttf $out/Roboto/
    ln -s ${pkgs.dejavu_fonts}/share/fonts/truetype/DejaVuSans{,-Bold,-Oblique,-BoldOblique}.ttf "$out/DejaVu Sans/"
  '';
in
{
  inherit fonts;

  shell = pkgs.mkShell {
    # Flutter brings its own Dart SDK and flutter_tester.
    packages = with pkgs; [
      flutter
      git
    ];
    # Read by runBombadil.
    BOMBADIL_FLUTTER_FONTS = fonts;
  };
}
