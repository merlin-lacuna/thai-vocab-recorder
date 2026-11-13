package app.thaivocab.boox

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.thaivocab.boox.ui.PreviewBoox
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test

/**
 * Draws the tablet screen on the computer (no tablet or emulator) and saves a PNG file.
 * Run:  ./gradlew :boox:recordPaparazziDebug
 * Output: boox/src/test/snapshots/images/
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        // Boox Note Air2 Plus: 1404 x 1872 pixels at 240 dpi.
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1404, screenHeight = 1872, xdpi = 240, ydpi = 240, density = Density.HIGH, softButtons = false,
        ),
        maxPercentDifference = 0.1,
    )

    @Test fun screen() = paparazzi.snapshot { PreviewBoox() }
}
