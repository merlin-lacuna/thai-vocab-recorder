package app.thaivocab.recorder

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.thaivocab.recorder.ui.PreviewEmpty
import app.thaivocab.recorder.ui.PreviewList
import app.thaivocab.recorder.ui.PreviewListDark
import app.thaivocab.recorder.ui.PreviewRecording
import org.junit.Rule
import org.junit.Test

/**
 * Draws the screens on the computer (no phone or emulator) and saves PNG files.
 * Run:  ./gradlew recordPaparazziDebug
 * Output: app/src/test/snapshots/images/
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Test fun list() = paparazzi.snapshot { PreviewList() }
    @Test fun listDark() = paparazzi.snapshot { PreviewListDark() }
    @Test fun recording() = paparazzi.snapshot { PreviewRecording() }
    @Test fun firstStart() = paparazzi.snapshot { PreviewEmpty() }
}
