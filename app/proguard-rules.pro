# Add project specific ProGuard rules here.
# By default, the flags in R8 will keep all application classes.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify problematic APIs:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# If you use reflection, R8 normally traces it automatically. However, if it fails,
# you can keep the classes and members needed by reflection on a case-by-case basis:
#-keep class MyClass { MyMembers }

# If you use libraries that use reflection, you may need to manually keep their classes
# and members to prevent them from being removed or renamed by R8.
#-keep class some.library.ClassToKeep
#-keepclassmembers class some.library.ClassToKeep { *; }

# For example, if you use Gson, you may need to add the following lines:
#-keep class com.google.gson.reflect.TypeToken { *; }
#-keep class * extends com.google.gson.TypeAdapter

# If you use a library that dynamically loads classes through reflection,
# and you want to keep all of its classes, you can use:
#-keep public class * extends my.library.dynamically.loaded.BaseClass

# If you are developing an SDK, you should publish your AAR with its own ProGuard file that
# R8 will automatically use when an application depends on your SDK.
# See https://developer.android.com/studio/build/shrink-code#optimization-aar
