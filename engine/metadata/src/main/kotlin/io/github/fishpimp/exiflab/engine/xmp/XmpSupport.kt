package io.github.fishpimp.exiflab.engine.xmp

import io.github.fishpimp.exiflab.engine.plan.XmpEdits

/** Thin layer over Adobe XMP Core. Owned by the XMP/planner agent. */
internal object XmpSupport {
    /**
     * Applies [edits] to [packet] (null = no existing XMP). Returns the new serialized packet, or null if the
     * result should be removed (removeAll, or every property gone).
     * Output is a complete `<?xpacket ...?>` packet with reasonable padding for in-place growth.
     */
    fun apply(packet: String?, edits: XmpEdits): String? = TODO("XmpSupport.apply")

    /** Writes a new sidecar or merges into an existing one (never drops unrelated properties). UTF-8 bytes. */
    fun sidecar(existing: ByteArray?, edits: XmpEdits): ByteArray = TODO("XmpSupport.sidecar")

    /** Flattened (namespace, path, value) triples for display and diffing. Arrays/structs expanded with indexes. */
    fun properties(packet: String): List<XmpProperty> = TODO("XmpSupport.properties")
}

internal data class XmpProperty(val namespace: String, val prefix: String, val path: String, val value: String)
