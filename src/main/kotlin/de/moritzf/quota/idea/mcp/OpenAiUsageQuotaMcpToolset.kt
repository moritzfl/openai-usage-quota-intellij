package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import de.moritzf.quota.idea.QuotaUsageService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exposes the latest OpenAI usage JSON through IntelliJ's MCP server.
 */
class OpenAiUsageQuotaMcpToolset : McpToolset {
    @McpTool(name = "openai_usage_quota")
    @McpDescription(description = "Returns the latest OpenAI usage quota response JSON.")
    fun openai_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshNowBlocking()

        val error = usageService.getLastError()
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastResponseJson()
        if (json.isNullOrBlank()) {
            return errorResult("No usage response available")
        }
        return successResult(json)
    }

    private fun successResult(text: String): McpToolCallResult {
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(text)), null, false)
    }

    private fun errorResult(errorMessage: String): McpToolCallResult {
        val errorJson = buildJsonObject { put("error", errorMessage) }.toString()
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(errorJson)), null, true)
    }
}
