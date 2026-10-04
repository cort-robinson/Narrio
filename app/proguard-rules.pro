-keepattributes Signature,InnerClasses,EnclosingMethod
# Vosk calls native code through JNA, which looks up these classes and members reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
