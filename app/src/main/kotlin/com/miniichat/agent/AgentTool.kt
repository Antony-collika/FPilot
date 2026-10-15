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

class AgentToolRegistry(tools: List<AgentTool> = emptyList()) {
    private val toolsByName: Map<String, AgentTool> = tools.associateBy { it.definition.name }

    init {
        require(toolsByName.size == tools.size) { "Agent tool names must be unique." }
    }

    fun definitions(): List<ToolDefinition> = toolsByName.values.map { it.definition }

    suspend fun execute(name: String, arguments: JsonObject): String {
        val tool = toolsByName[name]
            ?: throw IllegalArgumentException("Unknown tool: $name")
        return tool.execute(arguments)
    }

    fun contains(name: String): Boolean = toolsByName.containsKey(name)
}
