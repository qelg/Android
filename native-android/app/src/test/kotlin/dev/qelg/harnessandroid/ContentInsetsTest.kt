package dev.qelg.harnessandroid

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentInsetsTest {
    private val density = Density(1f)

    @Test
    fun combinesSafeAreaAndImeWithoutAddingTheirBottomInsets() {
        val safeDrawing = WindowInsets(left = 40, top = 72, right = 24, bottom = 32)
        val ime = WindowInsets(bottom = 300)

        val result = contentInsets(safeDrawing, ime)

        assertEquals(40, result.getLeft(density, LayoutDirection.Ltr))
        assertEquals(72, result.getTop(density))
        assertEquals(24, result.getRight(density, LayoutDirection.Ltr))
        assertEquals(300, result.getBottom(density))
    }

    @Test
    fun keepsSafeBottomInsetWhenImeIsHidden() {
        val safeDrawing = WindowInsets(bottom = 32)
        val ime = WindowInsets(bottom = 0)

        val result = contentInsets(safeDrawing, ime)

        assertEquals(32, result.getBottom(density))
    }
}
