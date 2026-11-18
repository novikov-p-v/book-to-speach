package app.booktts.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The few Material icons that are not in material-icons-core. Defined here instead of
 * depending on material-icons-extended (thousands of icons, many MB of code).
 */
object AppIcons {
    val Pause by lazy { icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val SkipNext by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val SkipPrevious by lazy { icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }
    val FastForward by lazy { icon("FastForward", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z") }
    val FastRewind by lazy { icon("FastRewind", "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z") }
    val Download by lazy { icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val FileOpen by lazy {
        icon("FileOpen", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM6,20V4h7v5h5v11H6z")
    }
    val MyLocation by lazy {
        icon(
            "MyLocation",
            "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11A8.994,8.994 0,0 0,13 3.06V1h-2v2.06" +
                "A8.994,8.994 0,0 0,3.06 11H1v2h2.06A8.994,8.994 0,0 0,11 20.94V23h2v-2.06A8.994,8.994 0,0 0,20.94 13H23v-2h-2.06z" +
                "M12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        )
    }
    val RecordVoiceOver by lazy {
        icon(
            "RecordVoiceOver",
            "M5,9a4,4 0,1 0,8 0a4,4 0,1 0,-8 0z" +
                "M9,15c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z" +
                "M16.76,5.36l-1.68,1.69c0.84,1.18 0.84,2.71 0,3.89l1.68,1.69c2.02,-2.02 2.02,-5.07 0,-7.27z" +
                "M20.07,2l-1.63,1.63c2.77,3.02 2.77,7.56 0,10.74L20.07,16c3.9,-3.89 3.91,-9.95 0,-14z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
