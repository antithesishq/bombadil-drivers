{
  description = "External drivers for Bombadil";

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
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          swift = import ./nix/swift.nix { inherit pkgs; };
        in
        {
          flutter = (import ./nix/flutter.nix { inherit pkgs; }).shell;
          android = import ./nix/android.nix { inherit nixpkgs system; };
          inherit swift;
          macos = swift;
          ios = swift;
        }
        // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
          linux = (import ./nix/linux.nix { inherit pkgs; }).shell;
        }
      );

      packages = forAllSystems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in
        {
          fonts = (import ./nix/flutter.nix { inherit pkgs; }).fonts;
        }
        // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
          linux = (import ./nix/linux.nix { inherit pkgs; }).package;
        }
      );
    };
}
