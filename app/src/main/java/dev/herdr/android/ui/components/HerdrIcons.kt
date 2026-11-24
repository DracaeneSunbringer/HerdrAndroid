package dev.herdr.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Crisp 1.8px-stroke linear vector icon set replacing system emojis across workbench UI.
 */
object HerdrIcons {

    private fun linearIcon(
        name: String,
        strokeWidth: Float = 1.8f,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

    private fun ImageVector.Builder.strokePath(
        strokeWidth: Float = 1.8f,
        pathData: PathBuilder.() -> Unit
    ) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathData
        )
    }

    val Terminal: ImageVector by lazy {
        linearIcon("HerdrTerminal") {
            strokePath {
                // Rounded frame (3, 5) to (21, 19)
                moveTo(5f, 5f)
                horizontalLineTo(19f)
                quadTo(21f, 5f, 21f, 7f)
                verticalLineTo(17f)
                quadTo(21f, 19f, 19f, 19f)
                horizontalLineTo(5f)
                quadTo(3f, 19f, 3f, 17f)
                verticalLineTo(7f)
                quadTo(3f, 5f, 5f, 5f)
                close()
                // Prompt >_
                moveTo(7f, 9f)
                lineTo(10f, 12f)
                lineTo(7f, 15f)
                moveTo(12f, 15f)
                horizontalLineTo(17f)
            }
        }
    }

    val Sparkle: ImageVector by lazy {
        linearIcon("HerdrSparkle") {
            strokePath {
                moveTo(12f, 3f)
                quadTo(12.6f, 11.4f, 21f, 12f)
                quadTo(12.6f, 12.6f, 12f, 21f)
                quadTo(11.4f, 12.6f, 3f, 12f)
                quadTo(11.4f, 11.4f, 12f, 3f)
                close()
            }
        }
    }

    val Bolt: ImageVector by lazy {
        linearIcon("HerdrBolt") {
            strokePath {
                moveTo(13f, 2f)
                lineTo(5f, 13f)
                horizontalLineTo(11.5f)
                lineTo(10.5f, 22f)
                lineTo(19f, 11f)
                horizontalLineTo(12.5f)
                close()
            }
        }
    }

    val FileCode: ImageVector by lazy {
        linearIcon("HerdrFileCode") {
            strokePath {
                moveTo(6f, 3f)
                horizontalLineTo(14f)
                lineTo(19f, 8f)
                verticalLineTo(21f)
                horizontalLineTo(6f)
                close()
                moveTo(14f, 3f)
                verticalLineTo(8f)
                horizontalLineTo(19f)
                moveTo(9.5f, 13f)
                horizontalLineTo(15.5f)
                moveTo(9.5f, 16.5f)
                horizontalLineTo(13.5f)
            }
        }
    }

    val GitDiff: ImageVector by lazy {
        linearIcon("HerdrGitDiff") {
            strokePath {
                moveTo(12f, 4f)
                verticalLineTo(12f)
                moveTo(8f, 8f)
                horizontalLineTo(16f)
                moveTo(8f, 18f)
                horizontalLineTo(16f)
            }
        }
    }

    val ClockQueue: ImageVector by lazy {
        linearIcon("HerdrClockQueue") {
            strokePath {
                moveTo(12f, 3.5f)
                arcTo(8.5f, 8.5f, 0f, true, true, 11.99f, 3.5f)
                moveTo(12f, 7.5f)
                verticalLineTo(12f)
                lineTo(15.2f, 13.8f)
            }
        }
    }

    val AlertTriangle: ImageVector by lazy {
        linearIcon("HerdrAlertTriangle") {
            strokePath {
                moveTo(12f, 4f)
                lineTo(21f, 19.5f)
                horizontalLineTo(3f)
                close()
                moveTo(12f, 10f)
                verticalLineTo(14f)
                moveTo(12f, 17f)
                horizontalLineTo(12.05f)
            }
        }
    }

    val InfoCircle: ImageVector by lazy {
        linearIcon("HerdrInfoCircle") {
            strokePath {
                moveTo(12f, 3.5f)
                arcTo(8.5f, 8.5f, 0f, true, true, 11.99f, 3.5f)
                moveTo(12f, 11.5f)
                verticalLineTo(16f)
                moveTo(12f, 8f)
                horizontalLineTo(12.05f)
            }
        }
    }

    val SlashCommand: ImageVector by lazy {
        linearIcon("HerdrSlashCommand") {
            strokePath {
                moveTo(6f, 4f)
                horizontalLineTo(18f)
                quadTo(20f, 4f, 20f, 6f)
                verticalLineTo(18f)
                quadTo(20f, 20f, 18f, 20f)
                horizontalLineTo(6f)
                quadTo(4f, 20f, 4f, 18f)
                verticalLineTo(6f)
                quadTo(4f, 4f, 6f, 4f)
                close()
                moveTo(14.5f, 7.5f)
                lineTo(9.5f, 16.5f)
            }
        }
    }

    val Keyboard: ImageVector by lazy {
        linearIcon("HerdrKeyboard") {
            strokePath {
                moveTo(5f, 6f)
                horizontalLineTo(19f)
                quadTo(21f, 6f, 21f, 8f)
                verticalLineTo(16f)
                quadTo(21f, 18f, 19f, 18f)
                horizontalLineTo(5f)
                quadTo(3f, 18f, 3f, 16f)
                verticalLineTo(8f)
                quadTo(3f, 6f, 5f, 6f)
                close()
                moveTo(7f, 10f)
                horizontalLineTo(7.1f)
                moveTo(10.5f, 10f)
                horizontalLineTo(10.6f)
                moveTo(13.5f, 10f)
                horizontalLineTo(13.6f)
                moveTo(17f, 10f)
                horizontalLineTo(17.1f)
                moveTo(8.5f, 14.5f)
                horizontalLineTo(15.5f)
            }
        }
    }

    val ImageFrame: ImageVector by lazy {
        linearIcon("HerdrImageFrame") {
            strokePath {
                moveTo(6f, 4f)
                horizontalLineTo(18f)
                quadTo(20f, 4f, 20f, 6f)
                verticalLineTo(18f)
                quadTo(20f, 20f, 18f, 20f)
                horizontalLineTo(6f)
                quadTo(4f, 20f, 4f, 18f)
                verticalLineTo(6f)
                quadTo(4f, 4f, 6f, 4f)
                close()
                moveTo(4.5f, 16.5f)
                lineTo(9.5f, 11.5f)
                lineTo(13.5f, 15.5f)
                lineTo(16.5f, 12.5f)
                lineTo(19.5f, 16.5f)
                moveTo(9f, 8.8f)
                horizontalLineTo(9.05f)
            }
        }
    }

    val Paperclip: ImageVector by lazy {
        linearIcon("HerdrPaperclip") {
            strokePath {
                moveTo(16.5f, 6.5f)
                lineTo(9f, 14f)
                arcTo(2.12f, 2.12f, 0f, false, false, 12f, 17f)
                lineTo(19.5f, 9.5f)
                arcTo(4.24f, 4.24f, 0f, false, false, 13.5f, 3.5f)
                lineTo(6f, 11f)
                arcTo(6.36f, 6.36f, 0f, false, false, 15f, 20f)
                lineTo(20.5f, 14.5f)
            }
        }
    }

    val StopSquare: ImageVector by lazy {
        linearIcon("HerdrStopSquare") {
            strokePath(strokeWidth = 2f) {
                moveTo(8f, 6.5f)
                horizontalLineTo(16f)
                quadTo(17.5f, 6.5f, 17.5f, 8f)
                verticalLineTo(16f)
                quadTo(17.5f, 17.5f, 16f, 17.5f)
                horizontalLineTo(8f)
                quadTo(6.5f, 17.5f, 6.5f, 16f)
                verticalLineTo(8f)
                quadTo(6.5f, 6.5f, 8f, 6.5f)
                close()
            }
        }
    }

    val ListTurns: ImageVector by lazy {
        linearIcon("HerdrListTurns") {
            strokePath {
                moveTo(9f, 6f)
                horizontalLineTo(20f)
                moveTo(9f, 12f)
                horizontalLineTo(20f)
                moveTo(9f, 18f)
                horizontalLineTo(20f)
                moveTo(4.5f, 6f)
                horizontalLineTo(4.6f)
                moveTo(4.5f, 12f)
                horizontalLineTo(4.6f)
                moveTo(4.5f, 18f)
                horizontalLineTo(4.6f)
            }
        }
    }

    val UnfoldMore: ImageVector by lazy {
        linearIcon("HerdrUnfoldMore") {
            strokePath {
                moveTo(8f, 9f)
                lineTo(12f, 5f)
                lineTo(16f, 9f)
                moveTo(8f, 15f)
                lineTo(12f, 19f)
                lineTo(16f, 15f)
            }
        }
    }

    val FoldLess: ImageVector by lazy {
        linearIcon("HerdrFoldLess") {
            strokePath {
                moveTo(8f, 5f)
                lineTo(12f, 9f)
                lineTo(16f, 5f)
                moveTo(8f, 19f)
                lineTo(12f, 15f)
                lineTo(16f, 19f)
            }
        }
    }

    val ArrowUp: ImageVector by lazy {
        linearIcon("HerdrArrowUp") {
            strokePath {
                moveTo(12f, 19f)
                verticalLineTo(5f)
                moveTo(6f, 11f)
                lineTo(12f, 5f)
                lineTo(18f, 11f)
            }
        }
    }
}
