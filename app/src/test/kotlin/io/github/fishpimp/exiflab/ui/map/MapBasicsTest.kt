package io.github.fishpimp.exiflab.ui.map

import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class MapBasicsTest {
    @Test
    fun `coordinates always use a dot, whatever the language`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("sv-SE"))
            assertThat(LatLng(59.3293, 18.0686).formatted()).isEqualTo("59.32930, 18.06860")
            assertThat(LatLng(-33.8688197, 151.2092961).formatted(COPY_DECIMALS)).isEqualTo("-33.868820, 151.209296")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `only real positions are valid`() {
        assertThat(LatLng(0.0, 0.0).isValid).isTrue()
        assertThat(LatLng(90.0, -180.0).isValid).isTrue()
        assertThat(LatLng(90.5, 0.0).isValid).isFalse()
        assertThat(LatLng(0.0, 180.1).isValid).isFalse()
        assertThat(LatLng(Double.NaN, 0.0).isValid).isFalse()
        assertThat(LatLng(0.0, Double.POSITIVE_INFINITY).isValid).isFalse()
    }

    @Test
    fun `the map view is started before it is resumed`() {
        val calls = RecordingCallbacks()
        val stepper = MapLifecycleStepper(calls)
        stepper.moveTo(Lifecycle.State.RESUMED)
        assertThat(calls.log).containsExactly("start", "resume").inOrder()
        assertThat(stepper.state).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun `leaving a resumed screen pauses, stops and destroys in order`() {
        val calls = RecordingCallbacks()
        val stepper = MapLifecycleStepper(calls)
        stepper.moveTo(Lifecycle.State.RESUMED)
        calls.log.clear()

        stepper.moveTo(Lifecycle.State.DESTROYED)
        assertThat(calls.log).containsExactly("pause", "stop", "destroy").inOrder()
    }

    @Test
    fun `background and foreground round trip`() {
        val calls = RecordingCallbacks()
        val stepper = MapLifecycleStepper(calls)
        stepper.moveTo(Lifecycle.State.RESUMED)
        stepper.moveTo(Lifecycle.State.CREATED)
        stepper.moveTo(Lifecycle.State.RESUMED)
        assertThat(calls.log).containsExactly("start", "resume", "pause", "stop", "start", "resume").inOrder()
    }

    @Test
    fun `a destroyed map view is never revived`() {
        val calls = RecordingCallbacks()
        val stepper = MapLifecycleStepper(calls)
        stepper.moveTo(Lifecycle.State.DESTROYED)
        stepper.moveTo(Lifecycle.State.RESUMED)
        stepper.moveTo(Lifecycle.State.DESTROYED)
        assertThat(calls.log).containsExactly("destroy")
    }

    private class RecordingCallbacks : MapLifecycleCallbacks {
        val log = mutableListOf<String>()
        override fun onStart() { log += "start" }
        override fun onResume() { log += "resume" }
        override fun onPause() { log += "pause" }
        override fun onStop() { log += "stop" }
        override fun onDestroy() { log += "destroy" }
    }
}
