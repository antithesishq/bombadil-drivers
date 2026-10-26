# Shared settings for the Now in Android experiment. Sourced by setup.sh
# and driver.sh.
NIA_COMMIT=2982e63cb691ad49cd009aab43dc206fee6699c2
NIA_DIR=${NIA_DIR:-$HOME/repositories/github.com/android/nowinandroid}
# Which build of the app to test: `debug` (demoDebug) or `benchmarkRelease`
# (demoBenchmarkRelease: R8-minified, not debuggable, signed with the debug
# key, plus agent-keep.pro so that the in-process agent can run in it).
NIA_VARIANT=${NIA_VARIANT:-benchmarkRelease}
case $NIA_VARIANT in
  debug)
    NIA_APK=$NIA_DIR/app/build/outputs/apk/demo/debug/app-demo-debug.apk
    NIA_PACKAGE=com.google.samples.apps.nowinandroid.demo.debug
    NIA_TASK=:app:assembleDemoDebug
    ;;
  benchmarkRelease)
    NIA_APK=$NIA_DIR/app/build/outputs/apk/demo/benchmarkRelease/app-demo-benchmarkRelease.apk
    NIA_PACKAGE=com.google.samples.apps.nowinandroid.demo
    NIA_TASK=:app:assembleDemoBenchmarkRelease
    ;;
  *)
    echo "NIA_VARIANT must be debug or benchmarkRelease, not $NIA_VARIANT" >&2
    return 1
    ;;
esac
# The in-process agent, signed with the key Gradle signed the app with.
NIA_AGENT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/build/agent/$NIA_PACKAGE.apk
NIA_KEYSTORE=${NIA_KEYSTORE:-$HOME/.android/debug.keystore}
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/bombadil-compose/avd}
AVD_NAME=nia35
AVD_IMAGE="system-images;android-35;default;x86_64"
