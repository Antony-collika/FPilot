package com.miniichat.agent

import com.miniichat.api.ToolDefinition
import kotlinx.serialization.json.JsonObject

/**
 * A capability exposed to the model. Tool implementations must validate every
 * argument and enforce their own authorization boundaries before doing work.
 */
interface AgentTool {
    val definition: ToolDefinition

    suspend fun execute(arguments: JsonObject): String
}

/**
 * A tool as the model sees it: [name] and [definition] come from the Tools card,
 * while [uid] is the internal id of the code that runs it.
 */
class ExposedTool(val uid: String, val name: String, val definition: ToolDefinition)

/**
 * Holds the tools the app can run. Without [exposed], tools are offered under their own names.
 * With [exposed] (the Tools card), the model sees exactly those names and descriptions,
 * and a call is routed to the tool with the matching uid. If the app has the tool turned off
 * (a switch in Settings), the call is refused with its own message.
 */
class AgentToolRegistry(tools: List<AgentTool> = emptyList(), private val exposed: List<ExposedTool>? = null) {
    private val toolsByUid: Map<String, AgentTool> = tools.associateBy { it.definition.name }
    private val exposedByName: Map<String, ExposedTool>? = exposed?.associateBy { it.name }

    init {
        require(toolsByUid.size == tools.size) { "Agent tool names must be unique." }
        if (exposed != null) {
            require(exposedByName!!.size == exposed.size) { "Exposed tool names must be unique." }
        }
    }

    fun definitions(): List<ToolDefinition> =
        exposed?.map { it.definition } ?: toolsByUid.values.map { it.definition }

    suspend fun execute(name: String, arguments: JsonObject): String {
        val uid = if (exposedByName != null) {
            exposedByName[name]?.uid ?: throw IllegalArgumentException("Unknown tool: $name")
        } else {
            name
        }
        val tool = toolsByUid[uid]
            ?: throw IllegalArgumentException(
                if (exposedByName != null) "The tool \"$name\" is turned off in the app settings." else "Unknown tool: $name"
            )
        return tool.execute(arguments)
    }

    fun contains(name: String): Boolean =
        if (exposedByName != null) exposedByName.containsKey(name) else toolsByUid.containsKey(name)
}
