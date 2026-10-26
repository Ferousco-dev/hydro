package com.ferousco.hydro.ai.litert

import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.tools.AllowAllToolPermissionGate
import com.ferousco.hydro.core.tools.HydroToolRegistry
import com.ferousco.hydro.core.tools.ToolCallOutcome
import com.ferousco.hydro.core.tools.ToolDescriptor
import com.ferousco.hydro.core.tools.ToolPermissionGate
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.runBlocking

/**
 * Adapts a single [ToolDescriptor] from [HydroToolRegistry] to LiteRT-LM's [OpenApiTool] contract.
 *
 * When `ConversationConfig(automaticToolCalling = false)` is used (Hydro's default with
 * `HydroGemmaOrchestrator`), LiteRT-LM uses [getToolDescriptionJsonString] solely for constrained
 * decoding / prompt preface and returns `Message.toolCalls` for non-blocking coroutine execution.
 * If a caller opts into `automaticToolCalling = true`, [execute] still routes strictly through
 * [HydroToolRegistry.executeJson] with full schema, permission, and timeout enforcement.
 */
class HydroLiteRtOpenApiTool(
    private val descriptor: ToolDescriptor,
    private val registry: HydroToolRegistry,
    private val permissionGate: ToolPermissionGate = AllowAllToolPermissionGate
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String =
        SafeJsonCodec.stringify(descriptor.toOpenApiFunctionDeclaration())

    override fun execute(paramsJsonString: String): String = runBlocking {
        when (val outcome = registry.executeJson(descriptor.name, paramsJsonString, permissionGate)) {
            is ToolCallOutcome.Success -> SafeJsonCodec.stringify(outcome.jsonResult)
            is ToolCallOutcome.Failure -> SafeJsonCodec.stringify(outcome.jsonError)
        }
    }
}

object HydroLiteRtToolBindings {
    fun buildToolProviders(
        registry: HydroToolRegistry,
        permissionGate: ToolPermissionGate = AllowAllToolPermissionGate
    ): List<ToolProvider> = registry.descriptors().map { descriptor ->
        tool(HydroLiteRtOpenApiTool(descriptor, registry, permissionGate))
    }
}
