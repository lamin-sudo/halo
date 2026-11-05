# The libxposed entry class is instantiated by name from META-INF/xposed/java_init.list, a text
# file R8 does not otherwise look at. Keep the class AND rewrite the file if it is ever renamed —
# the store bot requires that file to name a real class, so a silent rename would ship a module
# that loads nowhere.
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class io.github.lamin_sudo.halo.IslandModule { *; }
-keep class io.github.lamin_sudo.halo.SettingsActivity { *; }

# Reflection targets inside SystemUI are resolved by name at runtime.
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker

# Never strip a Throwable that a hook boundary depends on.
-keepattributes *Annotation*, InnerClasses, Signature
