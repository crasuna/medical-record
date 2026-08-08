package com.loveluke.medicalrecord.feature.encounter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentImageDecoderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun decryptedImagePreviewAppliesAllEightExifOrientations() {
        orientationCases.forEach { case ->
            val imageFile = temporaryFolder.newFile("orientation-${case.exifValue}.jpg")
            writeAsymmetricJpeg(imageFile)
            ExifInterface(imageFile).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, case.exifValue.toString())
                saveAttributes()
            }

            val rendered = decodeSampledImage(imageFile)?.asAndroidBitmap()

            assertNotNull("orientation=${case.exifValue}", rendered)
            assertColorGrid(
                bitmap = requireNotNull(rendered),
                expectedColorIndexes = case.expectedColorIndexes,
                message = "orientation=${case.exifValue}",
            )
        }
    }

    private fun writeAsymmetricJpeg(file: File) {
        val bitmap = createBitmap(RAW_WIDTH, RAW_HEIGHT)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { style = Paint.Style.FILL }
        fixtureColors.forEachIndexed { index, color ->
            val row = index / RAW_COLUMNS
            val column = index % RAW_COLUMNS
            paint.color = color
            canvas.drawRect(
                (column * CELL_SIZE).toFloat(),
                (row * CELL_SIZE).toFloat(),
                ((column + 1) * CELL_SIZE).toFloat(),
                ((row + 1) * CELL_SIZE).toFloat(),
                paint,
            )
        }
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        }
        bitmap.recycle()
    }

    private fun assertColorGrid(
        bitmap: Bitmap,
        expectedColorIndexes: Array<IntArray>,
        message: String,
    ) {
        val expectedRows = expectedColorIndexes.size
        val expectedColumns = expectedColorIndexes.first().size
        assertEquals(message, expectedColumns * CELL_SIZE, bitmap.width)
        assertEquals(message, expectedRows * CELL_SIZE, bitmap.height)
        expectedColorIndexes.forEachIndexed { row, expectedRow ->
            expectedRow.forEachIndexed { column, expectedColorIndex ->
                val actualColor = bitmap.getPixel(
                    column * CELL_SIZE + CELL_SIZE / 2,
                    row * CELL_SIZE + CELL_SIZE / 2,
                )
                assertEquals(
                    "$message row=$row column=$column",
                    expectedColorIndex,
                    nearestFixtureColorIndex(actualColor),
                )
            }
        }
    }

    private fun nearestFixtureColorIndex(actual: Int): Int = fixtureColors.indices.minBy { index ->
        val expected = fixtureColors[index]
        square(Color.red(actual) - Color.red(expected)) +
            square(Color.green(actual) - Color.green(expected)) +
            square(Color.blue(actual) - Color.blue(expected))
    }

    private fun square(value: Int): Int = value * value

    private data class OrientationCase(
        val exifValue: Int,
        val expectedColorIndexes: Array<IntArray>,
    )

    private companion object {
        const val CELL_SIZE = 40
        const val RAW_COLUMNS = 3
        const val RAW_WIDTH = RAW_COLUMNS * CELL_SIZE
        const val RAW_HEIGHT = 2 * CELL_SIZE

        val fixtureColors = intArrayOf(
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.CYAN,
            Color.MAGENTA,
            Color.YELLOW,
        )

        val orientationCases = listOf(
            OrientationCase(
                ExifInterface.ORIENTATION_NORMAL,
                arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5)),
            ),
            OrientationCase(
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                arrayOf(intArrayOf(2, 1, 0), intArrayOf(5, 4, 3)),
            ),
            OrientationCase(
                ExifInterface.ORIENTATION_ROTATE_180,
                arrayOf(intArrayOf(5, 4, 3), intArrayOf(2, 1, 0)),
            ),
            OrientationCase(
                ExifInterface.ORIENTATION_FLIP_VERTICAL,
                arrayOf(intArrayOf(3, 4, 5), intArrayOf(0, 1, 2)),
            ),
            OrientationCase(
                ExifInterface.ORIENTATION_TRANSPOSE,
                arrayOf(intArrayOf(0, 3), intArrayOf(1, 4), intArrayOf(2, 5)),
            ),
            OrientationCase(
                ExifInterface.ORIENTATION_ROTATE_90,
                arrayOf(intArrayOf(3, 0), intArrayOf(4, 1), intArrayOf(5, 2)),
            ),
            OrientationCase(
                ExifInterface.ORIENTATION_TRANSVERSE,
                arrayOf(intArrayOf(5, 2), intArrayOf(4, 1), intArrayOf(3, 0)),
            ),
            OrientationCase(
                ExifInterface.ORIENTATION_ROTATE_270,
                arrayOf(intArrayOf(2, 5), intArrayOf(1, 4), intArrayOf(0, 3)),
            ),
        )
    }
}
