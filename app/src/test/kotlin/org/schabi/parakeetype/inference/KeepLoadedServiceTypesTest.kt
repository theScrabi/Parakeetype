package org.schabi.parakeetype.inference

import android.content.pm.ServiceInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The keep-loaded foreground service must carry the microphone type: on Android 17 the
 * platform binds a third-party recognition service without passing on the calling app's
 * capabilities, so recording from [org.schabi.parakeetype.recognition.ParakeetypeRecognitionService]
 * (and from the keyboard while it is hidden) only works while Parakeetype's own process holds
 * the microphone capability. Without the RECORD_AUDIO permission the microphone type is
 * rejected, so specialUse alone is the fallback.
 */
class KeepLoadedServiceTypesTest {

    @Test
    fun `given Android 14 or newer, when entering keep-loaded mode, then microphone plus specialUse is tried first`() {
        assertThat(keepLoadedServiceTypes(34)).containsExactly(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        assertThat(keepLoadedServiceTypes(37)).isEqualTo(keepLoadedServiceTypes(34))
    }

    @Test
    fun `given Android 13 or older, when entering keep-loaded mode, then the manifest types are used`() {
        // Type 0 = all types the manifest declares (microphone|specialUse).
        assertThat(keepLoadedServiceTypes(31)).containsExactly(0)
        assertThat(keepLoadedServiceTypes(33)).containsExactly(0)
    }
}
