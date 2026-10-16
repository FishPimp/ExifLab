# kotlinx.serialization keeps serializers for @Serializable navigation keys via its own consumer rules.

# metadata-extractor (used by :core:metadata) creates directories reflectively
# (DirectoryTiffHandler.pushDirectory calls Class.newInstance), so the no-argument constructors of
# directory classes must survive shrinking.
-keepclassmembers class * extends com.drew.metadata.Directory {
    public <init>();
}

# Release builds log nothing below errors, including from libraries: log lines can carry file
# names, URIs or metadata values, and ExifLab keeps those on the device.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
