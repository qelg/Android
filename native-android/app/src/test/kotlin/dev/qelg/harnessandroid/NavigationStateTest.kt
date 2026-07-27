package dev.qelg.harnessandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationStateTest {
    @Test
    fun selectingChatFromTreeKeepsTreeForBackNavigation() {
        assertEquals("root", treeParentAfterSelection("root", selectedFromTree = true))
    }

    @Test
    fun selectingChatOutsideTreeClearsPreviousTree() {
        assertNull(treeParentAfterSelection("root", selectedFromTree = false))
    }
}
