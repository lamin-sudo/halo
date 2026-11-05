# The libxposed entry class is instantiated by the framework by name, so R8 must not
# rename or remove it, nor any of the hook callbacks it installs.
-keep class dz.island.a79.IslandModule { *; }
-keep class dz.island.a79.SettingsActivity { *; }

# Reflection targets inside SystemUI are resolved by name at runtime.
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker

# Never strip a Throwable that a hook boundary depends on.
-keepattributes *Annotation*, InnerClasses, Signature
