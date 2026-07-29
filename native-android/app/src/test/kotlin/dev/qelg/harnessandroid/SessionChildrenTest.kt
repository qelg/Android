package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.HarnessSession
import dev.qelg.harnessandroid.data.directChildSessions
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionChildrenTest {
    @Test
    fun returnsOnlySessionsWhoseParentIsTheSelectedSession() {
        val sessions =
            listOf(
                HarnessSession("parent", "Parent"),
                HarnessSession(
                    "older",
                    "Older child",
                    updatedAt = "2025-01-01T00:00:00Z",
                    parentSessionId = "parent",
                ),
                HarnessSession(
                    "newer",
                    "Newer child",
                    updatedAt = "2025-02-01T00:00:00Z",
                    parentSessionId = "parent",
                ),
                HarnessSession("grandchild", "Grandchild", parentSessionId = "newer"),
                HarnessSession("unrelated", "Unrelated", parentSessionId = "other"),
            )

        assertEquals(
            listOf("newer", "older"),
            directChildSessions(sessions, "parent").map { it.id },
        )
    }
}
