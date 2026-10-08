package io.github.fishpimp.exiflab.engine.iptc

import io.github.fishpimp.exiflab.engine.plan.IptcEdits

internal data class IptcDataset(val record: Int, val dataset: Int, val value: ByteArray)

/** IPTC-IIM parse/serialize and Photoshop IRB (APP13 "Photoshop 3.0") handling. Owned by the JPEG agent. */
internal object IptcCodec {
    fun parse(iim: ByteArray): List<IptcDataset> = TODO("IptcCodec.parse")

    fun serialize(datasets: List<IptcDataset>): ByteArray = TODO("IptcCodec.serialize")

    /** Applies edits; writes UTF-8 with the CodedCharacterSet (1:90) marker. Returns null if nothing remains. */
    fun apply(iim: ByteArray?, edits: IptcEdits): ByteArray? = TODO("IptcCodec.apply")

    /** Extracts IIM bytes from a Photoshop IRB (resource 0x0404). */
    fun fromPhotoshopIrb(irb: ByteArray): ByteArray? = TODO("IptcCodec.fromPhotoshopIrb")

    /**
     * Rebuilds an IRB with new IIM data (null removes resource 0x0404). Keeps other resources; removes the
     * IPTC digest (0x0425) so readers do not flag a mismatch. Returns null if the IRB would be empty.
     */
    fun toPhotoshopIrb(existingIrb: ByteArray?, iim: ByteArray?): ByteArray? = TODO("IptcCodec.toPhotoshopIrb")
}
