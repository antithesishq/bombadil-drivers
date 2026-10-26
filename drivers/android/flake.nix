{
  description = "Bombadil external driver SDK for Jetpack Compose (Kotlin)";

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
      forAllSystems =
        f:
        nixpkgs.lib.genAttrs systems (
          system:
          f (
            import nixpkgs {
              inherit system;
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
            }
          )
        );
    in
    {
      devShells = forAllSystems (
        pkgs:
        let
          abi = if pkgs.stdenv.hostPlatform.isAarch64 then "arm64-v8a" else "x86_64";
          # What nowinandroid needs to build (compileSdk 37) and an API 35
          # emulator image to run it on.
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [
              "35"
              "37.0"
            ];
            buildToolsVersions = [
              "36.0.0"
              "37.0.0"
            ];
            includeEmulator = true;
            includeSystemImages = true;
            systemImageTypes = [ "default" ];
            abiVersions = [ abi ];
          };
          sdk = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              gradle
              jdk21
              kotlin
              android.androidsdk
              git
              jq
            ];
            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;
            # Gradle's aapt2 from Maven is dynamically linked and doesn't run
            # on NixOS; use the SDK's.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/37.0.0/aapt2";
            JAVA_HOME = pkgs.jdk21.home;
            # For running the emulator on the host's GPU without its
            # launcher (see examples/nowinandroid/setup.sh): libglvnd
            # loads the host's EGL driver.
            GLVND_LIB = pkgs.lib.optionalString pkgs.stdenv.isLinux "${pkgs.libglvnd}/lib";
          };
        }
      );
    };
}
