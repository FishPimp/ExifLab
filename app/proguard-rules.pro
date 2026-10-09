# kotlinx.serialization keeps serializers for @Serializable navigation keys via its own consumer rules.

# metadata-extractor (used by :core:metadata) creates directories reflectively
# (DirectoryTiffHandler.pushDirectory calls Class.newInstance), so the no-argument constructors of
# directory classes must survive shrinking.
-keepclassmembers class * extends com.drew.metadata.Directory {
    public <init>();
}
