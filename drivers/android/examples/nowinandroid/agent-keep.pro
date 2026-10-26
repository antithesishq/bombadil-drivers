# Keeps the classes the in-process agent (android-agent/) links against or
# reads through reflection, under their own names, in an R8-minified build.
# The agent is dexed without its dependencies and uses the app's copies, so
# without these rules R8 would rename or remove them. Everything else in the
# app is optimised and obfuscated as in a release build. Update this list
# when the agent starts using another class.

-keep class androidx.compose.runtime.Recomposer { *; }
-keep class androidx.compose.runtime.Recomposer$* { *; }
-keep class androidx.compose.runtime.RecomposerInfo { *; }
-keep class androidx.compose.runtime.snapshots.Snapshot { *; }
-keep class androidx.compose.runtime.snapshots.Snapshot$Companion { *; }
-keep class androidx.compose.ui.geometry.Rect { *; }
-keep class androidx.compose.ui.layout.LayoutInfo { *; }
-keep class androidx.compose.ui.node.RootForTest { *; }
-keep class androidx.compose.ui.platform.ViewRootForTest { *; }
-keep class androidx.compose.ui.platform.ViewRootForTest$Companion { *; }
-keep class androidx.compose.ui.semantics.AccessibilityAction { *; }
-keep class androidx.compose.ui.semantics.Role { *; }
-keep class androidx.compose.ui.semantics.Role$Companion { *; }
-keep class androidx.compose.ui.semantics.ScrollAxisRange { *; }
-keep class androidx.compose.ui.semantics.SemanticsActions { *; }
-keep class androidx.compose.ui.semantics.SemanticsConfiguration { *; }
-keep class androidx.compose.ui.semantics.SemanticsNode { *; }
-keep class androidx.compose.ui.semantics.SemanticsOwner { *; }
-keep class androidx.compose.ui.semantics.SemanticsProperties { *; }
-keep class androidx.compose.ui.semantics.SemanticsPropertiesAndroid { *; }
-keep class androidx.compose.ui.semantics.SemanticsPropertyKey { *; }
-keep class androidx.compose.ui.state.ToggleableState { *; }
-keep class androidx.compose.ui.text.AnnotatedString { *; }

-keep class kotlin.Function { *; }
-keep class kotlin.Lazy { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.Pair { *; }
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Companion { *; }
-keep class kotlin.ResultKt { *; }
-keep class kotlin.TuplesKt { *; }
-keep class kotlin.Unit { *; }
-keep class kotlin.collections.ArraysKt { *; }
-keep class kotlin.collections.CollectionsKt { *; }
-keep class kotlin.collections.MapsKt { *; }
-keep class kotlin.collections.SetsKt { *; }
-keep class kotlin.io.CloseableKt { *; }
-keep class kotlin.jvm.functions.Function0 { *; }
-keep class kotlin.jvm.functions.Function1 { *; }
-keep class kotlin.jvm.functions.Function2 { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.jvm.internal.Ref$ObjectRef { *; }
-keep class kotlin.math.MathKt { *; }
-keep class kotlin.text.Charsets { *; }
-keep class kotlin.text.MatchResult { *; }
-keep class kotlin.text.Regex { *; }
-keep class kotlin.text.StringsKt { *; }

-keep class kotlinx.coroutines.CoroutineDispatcher { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.flow.StateFlow { *; }
# Read through reflection: the scheduler behind Dispatchers.Default and IO.
-keep class kotlinx.coroutines.scheduling.SchedulerCoroutineDispatcher { *; }
-keep class kotlinx.coroutines.scheduling.CoroutineScheduler { *; }
