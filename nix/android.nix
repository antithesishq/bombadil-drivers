{ nixpkgs, system }:
let
  pkgs = import nixpkgs {
    inherit system;
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };
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
pkgs.mkShell {
  packages =
    with pkgs;
    [
      gradle
      jdk21
      kotlin
      android.androidsdk
      git
      jq
    ]
    # setsid, for drivers/android/examples/nowinandroid/setup.sh.
    ++ pkgs.lib.optional pkgs.stdenv.isLinux pkgs.util-linux;
  ANDROID_HOME = sdk;
  ANDROID_SDK_ROOT = sdk;
  # Gradle's aapt2 from Maven is dynamically linked and doesn't run
  # on NixOS; use the SDK's.
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/37.0.0/aapt2";
  JAVA_HOME = pkgs.jdk21.home;
  # For running the emulator on the host's GPU without its
  # launcher (see drivers/android/examples/nowinandroid/setup.sh): libglvnd
  # loads the host's EGL driver.
  GLVND_LIB = pkgs.lib.optionalString pkgs.stdenv.isLinux "${pkgs.libglvnd}/lib";
}
