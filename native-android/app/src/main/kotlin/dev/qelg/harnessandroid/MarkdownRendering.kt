package dev.qelg.harnessandroid

import android.content.Context
import android.text.Spanned
import android.widget.TextView
import dev.qelg.harnessandroid.data.ChatItem
import dev.qelg.harnessandroid.data.isSafeExternalUrl
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

internal fun shouldRenderMarkdown(message: ChatItem.Message): Boolean =
    message.role == "assistant" && !message.pendingCanonical

internal fun configureMarkdownTextView(textView: TextView) {
    textView.setTextIsSelectable(true)
    textView.movementMethod = TableAwareMovementMethod.create()
}

internal fun containsMarkdownTable(rendered: Spanned): Boolean =
    rendered.getSpans(0, rendered.length, TableRowSpan::class.java).isNotEmpty()

internal fun markdownRenderer(context: Context): Markwon = MarkdownRenderer.get(context)

private object MarkdownRenderer {
    @Volatile private var instance: Markwon? = null

    fun get(context: Context): Markwon =
        instance
            ?: synchronized(this) {
                instance
                    ?: create(context.applicationContext).also { renderer -> instance = renderer }
            }

    private fun create(context: Context): Markwon {
        val defaultLinkResolver = LinkResolverDef()
        return Markwon.builder(context)
            .usePlugin(LinkifyPlugin.create(android.text.util.Linkify.WEB_URLS))
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver { view, link ->
                            if (isSafeExternalUrl(link)) defaultLinkResolver.resolve(view, link)
                        }
                    }
                }
            )
            .build()
    }
}
