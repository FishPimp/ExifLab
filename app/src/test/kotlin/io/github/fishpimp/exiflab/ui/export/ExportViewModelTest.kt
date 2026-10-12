package io.github.fishpimp.exiflab.ui.export

import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.materialkolor.dynamicColorScheme
import io.github.fishpimp.exiflab.data.export.ExportFormat
import io.github.fishpimp.exiflab.data.export.ExportResult
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.data.export.MetadataExporter
import io.github.fishpimp.exiflab.data.export.PdfExport
import io.github.fishpimp.exiflab.data.export.pdf.PdfPalette
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.screenshots.FakePhotos
import io.github.fishpimp.exiflab.screenshots.FakeReports
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExportViewModelTest {
    private val documents = FakeDocuments()
    private val palette = PdfPalette(dynamicColorScheme(Color(0xFF1E8C93), isDark = false), dynamicColorScheme(Color(0xFFD9480F), isDark = false))
    private val destination: Uri = Uri.parse("content://fake.documents/export/1")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        read: suspend (PhotoRef) -> io.github.fishpimp.exiflab.metadata.model.MetadataReport = { FakeReports.phoneJpeg },
        pdf: PdfExport = PdfExport { photos, _, _, output, _ ->
            output.write("%PDF-1.4".toByteArray())
            ExportResult(photos.size, emptyList())
        },
    ) = ExportViewModel(
        exporter = MetadataExporter(read, pdf, "0.1.0"),
        documents = documents,
        mapOffline = flowOf(true),
        today = { LocalDate.of(2026, 10, 12) },
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test fun openingPicksADefaultFormatAndSuggestsAName() {
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        val state = vm.current.value
        assertThat(state.sheetOpen).isTrue()
        assertThat(state.settings.format).isEqualTo(ExportFormat.Pdf)
        assertThat(vm.suggestedFileName()).isEqualTo("PXL_20260614_174207123-metadata.pdf")

        vm.open(FakePhotos.recents, folderName = "Camera")
        assertThat(vm.current.value.settings.format).isEqualTo(ExportFormat.Csv)
        assertThat(vm.suggestedFileName()).isEqualTo("Camera-metadata.csv")
    }

    @Test fun choicesAreKeptForTheNextExportOfTheSameKind() {
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        vm.updateSettings(vm.current.value.settings.copy(format = ExportFormat.Json, redactSensitive = true))
        vm.dismiss()
        assertThat(vm.current.value.sheetOpen).isFalse()
        vm.open(listOf(FakeReports.rawRef))
        assertThat(vm.current.value.settings).isEqualTo(ExportSettings(format = ExportFormat.Json, redactSensitive = true))
        assertThat(vm.suggestedFileName()).isEqualTo("DSCF4410-metadata.json")
    }

    @Test fun mapAvailabilityReachesTheState() {
        val vm = viewModel()
        assertThat(await { vm.state.first { it.mapOffline } }.mapOffline).isTrue()
    }

    @Test fun confirmAsksForADestinationThenWritesTheFile() {
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        vm.updateSettings(vm.current.value.settings.copy(format = ExportFormat.Csv))
        vm.confirm()
        assertThat(vm.current.value.sheetOpen).isFalse()
        assertThat(await { vm.events.first() }).isEqualTo(ExportEvent.ChooseDestination("PXL_20260614_174207123-metadata.csv", "text/csv"))

        vm.onDestinationChosen(destination, palette)
        val finished = await { vm.events.first() }
        assertThat(finished).isEqualTo(ExportEvent.Finished(destination, "text/csv", exported = 1, failed = 0, single = true))
        assertThat(documents.written(destination)).startsWith("﻿file,format,directory")
        assertThat(documents.deleted).isEmpty()
        assertThat(vm.current.value.progress).isNull()
    }

    @Test fun backingOutOfTheSystemDialogDoesNothing() {
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        vm.confirm()
        vm.onDestinationChosen(null, palette)
        assertThat(vm.current.value.progress).isNull()
        assertThat(documents.opened).isEmpty()
    }

    @Test fun unreadablePhotosLeaveNoFileBehind() {
        val vm = viewModel(read = { throw IOException("gone") })
        vm.open(listOf(FakeReports.phoneRef, FakeReports.rawRef))
        vm.confirm()
        await { vm.events.first() }
        vm.onDestinationChosen(destination, palette)
        assertThat(await { vm.events.first() }).isEqualTo(ExportEvent.Failed(ExportError.Unreadable, single = false))
        assertThat(documents.deleted).containsExactly(destination)
    }

    @Test fun someUnreadablePhotosAreCounted() {
        val vm = viewModel(read = { ref -> if (ref == FakeReports.rawRef) throw IOException("gone") else FakeReports.phoneJpeg })
        vm.open(listOf(FakeReports.phoneRef, FakeReports.rawRef))
        vm.updateSettings(vm.current.value.settings.copy(format = ExportFormat.Json))
        vm.confirm()
        await { vm.events.first() }
        vm.onDestinationChosen(destination, palette)
        assertThat(await { vm.events.first() }).isEqualTo(ExportEvent.Finished(destination, "application/json", exported = 1, failed = 1, single = false))
    }

    @Test fun writeErrorsAreReportedAndCleanedUp() {
        documents.failOpen = true
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        vm.confirm()
        await { vm.events.first() }
        vm.onDestinationChosen(destination, palette)
        assertThat(await { vm.events.first() }).isEqualTo(ExportEvent.Failed(ExportError.Write, single = true))
        assertThat(documents.deleted).containsExactly(destination)
    }

    @Test fun pdfFailuresSaySo() {
        val vm = viewModel(pdf = PdfExport { _, _, _, _, _ -> throw IllegalStateException("page too big") })
        vm.open(listOf(FakeReports.phoneRef))
        vm.confirm()
        await { vm.events.first() }
        vm.onDestinationChosen(destination, palette)
        assertThat(await { vm.events.first() }).isEqualTo(ExportEvent.Failed(ExportError.Pdf, single = true))
    }

    @Test fun cancellingRemovesTheHalfWrittenFile() {
        val started = CompletableDeferred<Unit>()
        val vm = viewModel(
            pdf = PdfExport { _, _, _, _, _ ->
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
                error("never finishes")
            },
        )
        vm.open(listOf(FakeReports.phoneRef))
        vm.confirm()
        await { vm.events.first() }
        vm.onDestinationChosen(destination, palette)
        await { started.await() }
        assertThat(vm.current.value.progress).isNotNull()
        vm.cancel()
        assertThat(await { vm.events.first() }).isEqualTo(ExportEvent.Cancelled)
        await { vm.current.first { it.progress == null } }
        assertThat(documents.deleted).containsExactly(destination)
    }

    private class FakeDocuments : ExportDocuments {
        val opened = mutableListOf<Uri>()
        val deleted = mutableListOf<Uri>()
        var failOpen = false
        private val outputs = mutableMapOf<Uri, ByteArrayOutputStream>()

        override fun openOutput(uri: Uri): OutputStream {
            if (failOpen) throw IOException("read-only")
            opened += uri
            return ByteArrayOutputStream().also { outputs[uri] = it }
        }

        override fun delete(uri: Uri) {
            deleted += uri
        }

        fun written(uri: Uri): String = outputs.getValue(uri).toString(Charsets.UTF_8)
    }
}
