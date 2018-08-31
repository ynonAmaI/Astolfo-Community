package xyz.astolfo.astolfocommunity.menus

import com.jagrosh.jdautilities.commons.utils.FinderUtil
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.commands.CommandSession
import xyz.astolfo.astolfocommunity.lib.commands.CommandScope
import xyz.astolfo.astolfocommunity.messages.*
import java.util.concurrent.atomic.AtomicReference

fun CommandScope.memberSelectionBuilder(query: String) = selectionBuilder<Member>()
        .results(FinderUtil.findMembers(query, event.guild))
        .noResultsMessage("Unknown Member!")
        .resultsRenderer { "**${it.effectiveName} (${it.user.name}#${it.user.discriminator})**" }
        .description("Type the number of the member you want.")

fun CommandScope.textChannelSelectionBuilder(query: String) = selectionBuilder<TextChannel>()
        .results(FinderUtil.findTextChannels(query, event.guild))
        .noResultsMessage("Unknown Text Channel!")
        .resultsRenderer { "**${it.name} (${it.id})**" }
        .description("Type the number of the text channel you want.")

fun CommandScope.roleSelectionBuilder(query: String) = selectionBuilder<Role>()
        .results(FinderUtil.findRoles(query, event.guild))
        .noResultsMessage("Unknown Role!")
        .resultsRenderer { "**${it.name} (${it.id})**" }
        .description("Type the number of the role you want.")

fun <E> CommandScope.selectionBuilder() = SelectionMenuBuilder<E>(this)

class SelectionMenuBuilder<E>(private val commandScope: CommandScope) {
    var title = "Selection Menu"
    var results = emptyList<E>()
    var noResultsMessage = "No results!"
    var resultsRenderer: (E) -> String = { it.toString() }
    var description = "Type the number of the selection you want"
    var renderer: Paginator.() -> Message = {
        message {
            embed {
                titleProvider.invoke()?.let { title(it) }
                description("$description\n$providedString")
                footer("Page ${currentPage + 1}/${provider.pageCount}")
            }
        }
    }

    fun title(value: String) = apply { title = value }
    fun results(value: List<E>) = apply { results = value }
    fun noResultsMessage(value: String) = apply { noResultsMessage = value }
    fun resultsRenderer(value: (E) -> String) = apply { resultsRenderer = value }
    fun renderer(value: Paginator.() -> Message) = apply { renderer = value }
    fun description(value: String) = apply { description = value }

    suspend fun execute(): E? = with(commandScope) {
        if (results.isEmpty()) {
            errorEmbed(noResultsMessage).queue()
            return null
        }

        if (results.size == 1) return results.first()

        val menu = paginator(title) {
            provider(8, results.map { resultsRenderer.invoke(it) })
            renderer { this@SelectionMenuBuilder.renderer.invoke(this) }
        }
        val response = CompletableDeferred<E?>()
        val errorMessage = AtomicReference<CachedMessage>()
        // Waits for a follow up response for user selection
        responseListener {
            if (menu.isDestroyed) {
                CommandSession.ResponseAction.UNREGISTER_LISTENER
            } else if (args.matches("\\d+".toRegex())) {
                val numSelection = args.toBigInteger().toInt()
                if (numSelection < 1 || numSelection > results.size) {
                    errorMessage.getAndSet(errorEmbed("Unknown Selection").send().sendCached())?.delete()
                    return@responseListener CommandSession.ResponseAction.IGNORE_COMMAND
                }
                val selectedMember = results[numSelection - 1]
                response.complete(selectedMember)
                CommandSession.ResponseAction.IGNORE_AND_UNREGISTER_LISTENER
            } else {
                if (event.message.contentRaw == args) {
                    errorMessage.getAndSet(errorEmbed("Response must be a number!").send().sendCached())?.delete()
                    return@responseListener CommandSession.ResponseAction.IGNORE_COMMAND
                } else CommandSession.ResponseAction.RUN_COMMAND
            }
        }
        val dispose = {
            synchronized(menu) {
                if (!menu.isDestroyed) menu.destroy()
            }
            errorMessage.getAndSet(null)?.delete()
        }
        return suspendCancellableCoroutine { cont ->
            val handle = response.invokeOnCompletion { t ->
                dispose()
                if (t == null) cont.resume(response.getCompleted())
                else cont.resumeWithException(t)
            }
            cont.invokeOnCancellation {
                dispose()
                handle.dispose()
            }
        }
    }
}

fun CommandScope.chatInput(inputMessage: String) = ChatInputBuilder(this)
        .description(inputMessage)

class ChatInputBuilder(private val execution: CommandScope) {
    private var title: String = ""
    private var description: String = "Input = Output"
    private var responseValidator: suspend (String) -> Boolean = { true }

    fun title(value: String) = apply { title = value }
    fun description(value: String) = apply { description = value }
    fun responseValidator(value: suspend (String) -> Boolean) = apply { responseValidator = value }

    suspend fun execute(): String? = with(execution) {
        val message = embed {
            if (title.isNotBlank()) title(title)
            description(description)
        }.send().sendCached()
        // Waits for a follow up response for user selection
        return suspendCancellableCoroutine { cont ->
            responseListener {
                if (message.isDeleted) {
                    CommandSession.ResponseAction.UNREGISTER_LISTENER
                } else {
                    val result = responseValidator.invoke(args)
                    if (result) {
                        // If the validator says its valid
                        cont.resume(args)
                        CommandSession.ResponseAction.IGNORE_AND_UNREGISTER_LISTENER
                    } else {
                        CommandSession.ResponseAction.IGNORE_COMMAND
                    }
                }
            }
            cont.invokeOnCancellation { message.delete() }
        }
    }
}