package dev.qelg.harnessandroid

import android.widget.TextView
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MarkdownTextViewTest {
    @Test
    fun selectableMarkdownTextKeepsClickableLinkMovementMethod() {
        val textView = TextView(RuntimeEnvironment.getApplication())

        configureMarkdownTextView(textView)

        assertTrue(textView.isTextSelectable)
        assertTrue(textView.movementMethod is TableAwareMovementMethod)
    }

    @Test
    fun renderedGfmTableRequestsConstrainedWidth() {
        val context = RuntimeEnvironment.getApplication()
        val rendered =
            markdownRenderer(context)
                .toMarkdown(
                    """
                    | Funktion | Status |
                    |---|---|
                    | Markdown | ✅ |
                    """
                        .trimIndent()
                )

        assertTrue(containsMarkdownTable(rendered))
    }
}
