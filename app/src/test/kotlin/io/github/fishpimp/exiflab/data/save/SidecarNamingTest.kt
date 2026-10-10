package io.github.fishpimp.exiflab.data.save

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.backup.Sha256
import io.github.fishpimp.exiflab.data.settings.SidecarNaming
import org.junit.Test

class SidecarNamingTest {
    @Test
    fun `base name replaces the extension`() {
        assertThat(SidecarNaming.BaseName.sidecarName("IMG_1234.RAF")).isEqualTo("IMG_1234.xmp")
        assertThat(SidecarNaming.BaseName.sidecarName("holiday.2026.CR3")).isEqualTo("holiday.2026.xmp")
        assertThat(SidecarNaming.BaseName.sidecarName("NOEXT")).isEqualTo("NOEXT.xmp")
        assertThat(SidecarNaming.BaseName.sidecarName(".hidden")).isEqualTo(".hidden.xmp")
    }

    @Test
    fun `full name keeps the extension`() {
        assertThat(SidecarNaming.FullName.sidecarName("IMG_1234.RAF")).isEqualTo("IMG_1234.RAF.xmp")
        assertThat(SidecarNaming.FullName.sidecarName("NOEXT")).isEqualTo("NOEXT.xmp")
    }

    @Test
    fun `sha256 matches known vectors`() {
        assertThat(Sha256.of(ByteArray(0)).sha256).isEqualTo(Sha256.EMPTY)
        assertThat(Sha256.of("abc".toByteArray()).sha256)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
