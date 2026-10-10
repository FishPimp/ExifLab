package io.github.fishpimp.exiflab.metadata.write.xmp

import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.XMPUtils
import com.adobe.internal.xmp.options.IteratorOptions
import com.adobe.internal.xmp.options.ParseOptions
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import com.adobe.internal.xmp.properties.XMPPropertyInfo
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import io.github.fishpimp.exiflab.metadata.write.XmpArrayKind
import io.github.fishpimp.exiflab.metadata.write.XmpChange
import java.security.MessageDigest

/** Parses, edits and serializes XMP packets with Adobe XMPCore. */
internal object XmpPackets {
    const val NS_XMP_NOTE = "http://ns.adobe.com/xmp/note/"
    private const val HAS_EXTENDED_XMP = "HasExtendedXMP"

    /** True when [changes] touch the XMP packet. */
    fun affects(changes: MetadataChanges): Boolean = changes.xmp.isNotEmpty() || MetadataBlock.Xmp in changes.removeBlocks

    fun empty(): XMPMeta = XMPMetaFactory.create()

    /** Parses [bytes]; a packet XMPCore cannot read is refused rather than silently replaced. */
    fun parse(bytes: ByteArray): XMPMeta = try {
        // Some writers pad the segment or chunk with NUL bytes after the packet.
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) end--
        val packet = if (end == bytes.size) bytes else bytes.copyOf(end)
        XMPMetaFactory.parseFromBuffer(packet, ParseOptions().setRequireXMPMeta(false).setFixControlChars(true).setAcceptLatin1(true))
    } catch (e: XMPException) {
        throw UnsupportedEditException("The XMP metadata is malformed and cannot be edited safely (${e.message})")
    }

    /**
     * Returns the packet [changes] produce from [existing] (null when there is none), or null when
     * no XMP should remain. Removing the XMP block runs before the property changes.
     */
    fun edit(existing: XMPMeta?, changes: MetadataChanges): XMPMeta? {
        val meta = if (MetadataBlock.Xmp in changes.removeBlocks) empty() else existing ?: empty()
        apply(meta, changes.xmp)
        return meta.takeIf { hasProperties(it) }
    }

    fun apply(meta: XMPMeta, changes: List<XmpChange>) {
        for (change in changes) {
            try {
                when (change) {
                    is XmpChange.SetProperty -> {
                        val name = qualified(change.namespace, change.prefix, change.name)
                        meta.deleteProperty(change.namespace, name)
                        meta.setProperty(change.namespace, name, change.value)
                    }
                    is XmpChange.SetArray -> {
                        val name = qualified(change.namespace, change.prefix, change.name)
                        meta.deleteProperty(change.namespace, name)
                        val options = when (change.kind) {
                            XmpArrayKind.Seq -> PropertyOptions().setArrayOrdered(true)
                            XmpArrayKind.Bag -> PropertyOptions().setArray(true)
                            XmpArrayKind.Alt -> PropertyOptions().setArrayAlternate(true)
                        }
                        for (value in change.values) meta.appendArrayItem(change.namespace, name, options, value, null)
                    }
                    is XmpChange.SetLocalizedText -> {
                        val name = qualified(change.namespace, change.prefix, change.name)
                        meta.deleteProperty(change.namespace, name)
                        meta.setLocalizedText(change.namespace, name, null, "x-default", change.value)
                    }
                    is XmpChange.Remove -> if (isRegistered(change.namespace)) meta.deleteProperty(change.namespace, change.name)
                    is XmpChange.RemoveNamespace -> if (isRegistered(change.namespace)) {
                        XMPUtils.removeProperties(meta, change.namespace, null, true, false)
                    }
                }
            } catch (e: XMPException) {
                throw UnsupportedEditException("Cannot apply XMP change $change: ${e.message}")
            }
        }
    }

    fun hasProperties(meta: XMPMeta): Boolean = try {
        meta.iterator(IteratorOptions().setJustLeafnodes(true)).hasNext()
    } catch (_: XMPException) {
        false
    }

    /** Serializes [meta] as UTF-8, compact, with an xpacket wrapper unless [wrapper] is false. */
    fun serialize(meta: XMPMeta, padding: Int = 0, wrapper: Boolean = true): ByteArray = try {
        val options = SerializeOptions().setUseCompactFormat(true).setOmitPacketWrapper(!wrapper)
        if (wrapper) options.setPadding(padding)
        XMPMetaFactory.serializeToBuffer(meta, options)
    } catch (e: XMPException) {
        throw UnsupportedEditException("Cannot write the XMP metadata: ${e.message}")
    }

    /** Serializes a sidecar: no packet wrapper, readable indentation (as Lightroom and darktable write them). */
    fun serializeSidecar(meta: XMPMeta): ByteArray = try {
        XMPMetaFactory.serializeToBuffer(meta, SerializeOptions().setOmitPacketWrapper(true))
    } catch (e: XMPException) {
        throw UnsupportedEditException("Cannot write the XMP sidecar: ${e.message}")
    }

    /** Main packet plus the extended part, as JPEG stores XMP that does not fit in one segment. */
    class JpegXmp(val standard: ByteArray, val extended: ByteArray?, val guid: String?)

    /** Folds an extended XMP packet back into its main packet and drops the link between them. */
    fun mergeExtended(main: XMPMeta, extended: ByteArray) {
        try {
            XMPUtils.appendProperties(parse(extended), main, true, true, false)
        } catch (e: XMPException) {
            throw UnsupportedEditException("The extended XMP metadata is malformed (${e.message})")
        }
        main.deleteProperty(NS_XMP_NOTE, HAS_EXTENDED_XMP)
    }

    fun extendedGuid(meta: XMPMeta): String? = try {
        meta.getPropertyString(NS_XMP_NOTE, HAS_EXTENDED_XMP)
    } catch (_: XMPException) {
        null
    }

    /**
     * Splits [meta] for JPEG following the XMP specification (part 3): when the serialized
     * packet exceeds [limit] bytes, top-level properties move to an extended packet,
     * photoshop:History first and then the largest ones, until the main packet fits. The main
     * packet then names the extended one by the MD5 digest of its serialization
     * (xmpNote:HasExtendedXMP).
     */
    fun packageForJpeg(source: XMPMeta, limit: Int): JpegXmp {
        val meta = source.clone() as XMPMeta
        meta.deleteProperty(NS_XMP_NOTE, HAS_EXTENDED_XMP)
        serialize(meta, padding = STANDARD_PADDING).let { if (it.size <= limit) return JpegXmp(it, null, null) }
        serialize(meta).let { if (it.size <= limit) return JpegXmp(it, null, null) }

        val standard = meta
        val extended = empty()
        standard.setProperty(NS_XMP_NOTE, HAS_EXTENDED_XMP, "0".repeat(GUID_LENGTH))
        val candidates = topLevelProperties(standard)
            .filterNot { it.namespace == NS_XMP_NOTE && it.path.endsWith(":$HAS_EXTENDED_XMP") }
            .map { it to estimatedSize(standard, it) }
            .sortedWith(compareByDescending<Pair<PropertyRef, Int>> { it.first.isPhotoshopHistory }.thenByDescending { it.second })
            .map { it.first }
        for (property in candidates) {
            if (serialize(standard).size <= limit) break
            try {
                XMPUtils.duplicateSubtree(standard, extended, property.namespace, property.path, property.namespace, property.path, null)
                standard.deleteProperty(property.namespace, property.path)
            } catch (e: XMPException) {
                throw UnsupportedEditException("Cannot split the XMP metadata for JPEG: ${e.message}")
            }
        }
        if (serialize(standard).size > limit) throw UnsupportedEditException("The XMP metadata is too large for a JPEG file")
        val extendedBytes = serialize(extended, wrapper = false)
        val guid = MessageDigest.getInstance("MD5").digest(extendedBytes).joinToString("") { "%02X".format(it) }
        standard.setProperty(NS_XMP_NOTE, HAS_EXTENDED_XMP, guid)
        return JpegXmp(serialize(standard), extendedBytes, guid)
    }

    private class PropertyRef(val namespace: String, val path: String) {
        val isPhotoshopHistory: Boolean get() = namespace == "http://ns.adobe.com/photoshop/1.0/" && path.endsWith(":History")
    }

    private fun topLevelProperties(meta: XMPMeta): List<PropertyRef> {
        val result = mutableListOf<PropertyRef>()
        val schemas = meta.iterator(IteratorOptions().setJustChildren(true))
        val namespaces = mutableListOf<String>()
        while (schemas.hasNext()) (schemas.next() as? XMPPropertyInfo)?.namespace?.let { namespaces += it }
        for (namespace in namespaces.distinct()) {
            val properties = meta.iterator(namespace, null, IteratorOptions().setJustChildren(true).setOmitQualifiers(true))
            while (properties.hasNext()) {
                val path = (properties.next() as? XMPPropertyInfo)?.path ?: continue
                if (path.isNotEmpty()) result += PropertyRef(namespace, path)
            }
        }
        return result
    }

    private fun estimatedSize(meta: XMPMeta, property: PropertyRef): Int = try {
        val single = empty()
        XMPUtils.duplicateSubtree(meta, single, property.namespace, property.path, property.namespace, property.path, null)
        serialize(single, wrapper = false).size
    } catch (_: XMPException) {
        0
    }

    private fun isRegistered(namespace: String): Boolean = XMPMetaFactory.getSchemaRegistry().getNamespacePrefix(namespace) != null

    /** Registers [namespace] if needed and returns [name] qualified with the prefix in use. */
    private fun qualified(namespace: String, prefix: String, name: String): String {
        val registry = XMPMetaFactory.getSchemaRegistry()
        val registered = registry.getNamespacePrefix(namespace) ?: registry.registerNamespace(namespace, prefix.removeSuffix(":"))
        val local = name.substringAfter(':')
        return registered.removeSuffix(":") + ":" + local
    }

    private const val STANDARD_PADDING = 2048
    private const val GUID_LENGTH = 32
}
