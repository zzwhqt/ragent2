/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.endpoint;

import com.nageoffer.ai.ragent.mcp.core.*;
import com.nageoffer.ai.ragent.mcp.protocol.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP JSON-RPC 方法分发器
 * <p>
 * 处理 initialize、tools/list、tools/call 三个核心方法
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MCPDispatcher {

    private final MCPToolRegistry toolRegistry;

    // ... existing code ...

    /**
     * 分发 MCP JSON-RPC 请求到对应的处理方法
     *
     * @param request 客户端发送的 JSON-RPC 请求对象
     * @return 处理后的响应对象，如果是 notification（无 id）则返回 null
     */
    public JsonRpcResponse dispatch(JsonRpcRequest request) {
        String method = request.getMethod();
        Object id = request.getId();

        // Notification（无 id）：仅记录日志，不返回响应体
        if (id == null) {
            log.debug("MCP notification received: {}", method);
            return null;
        }

        return switch (method) {
            case "initialize" -> handleInitialize(id);
            case "tools/list" -> handleToolsList(id);
            case "tools/call" -> handleToolsCall(id, request.getParams());
            default -> JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND, "Unknown method: " + method);
        };
    }

    /**
     * 处理 initialize 握手请求，返回服务器信息和协议版本
     *
     * @param id 请求 ID
     * @return 包含协议版本、能力声明和服务器信息的成功响应
     */
    private JsonRpcResponse handleInitialize(Object id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2026-02-28");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        result.put("capabilities", capabilities);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "ragent-mcp-server");
        serverInfo.put("version", "0.0.1");
        result.put("serverInfo", serverInfo);

        return JsonRpcResponse.success(id, result);
    }

    /**
     * 处理 tools/list 请求，列出所有已注册的 MCP 工具
     *
     * @param id 请求 ID
     * @return 包含工具 schema 列表的成功响应
     */
    private JsonRpcResponse handleToolsList(Object id) {
        List<MCPToolDefinition> tools = toolRegistry.listAllTools();

        List<MCPToolSchema> schemas = tools.stream().map(this::toSchema).toList();

        return JsonRpcResponse.success(id, Map.of("tools", schemas));
    }

    /**
     * 处理 tools/call 请求，执行指定的 MCP 工具
     *
     * @param id 请求 ID
     * @param params 请求参数，包含 tool name 和 arguments
     * @return 工具执行结果或错误信息
     */
    private JsonRpcResponse handleToolsCall(Object id, Map<String, Object> params) {
        if (params == null || !params.containsKey("name") || params.get("name") == null) {
            return JsonRpcResponse.error(id, JsonRpcError.INVALID_PARAMS, "Missing 'name' in params");
        }

        String toolName = String.valueOf(params.get("name"));

        // 通过 name 找到对应的 executor（name 即 toolId）
        Optional<MCPToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isEmpty()) {
            return JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND, "Tool not found: " + toolName);
        }

        // 解析 arguments
        Map<String, Object> arguments = new HashMap<>();
        Object rawArguments = params.get("arguments");
        if (rawArguments instanceof Map<?, ?> argMap) {
            for (Map.Entry<?, ?> entry : argMap.entrySet()) {
                if (entry.getKey() != null) {
                    arguments.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }

        MCPToolRequest toolRequest = MCPToolRequest.builder()
                .toolId(toolName)
                .parameters(arguments)
                .build();

        try {
            MCPToolResponse toolResponse = executorOpt.get().execute(toolRequest);

            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", toolResponse.getTextResult() != null ? toolResponse.getTextResult() : "");
            content.add(textContent);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content);
            result.put("isError", !toolResponse.isSuccess());

            return JsonRpcResponse.success(id, result);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);

            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", "工具调用异常: " + e.getMessage());
            content.add(textContent);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content);
            result.put("isError", true);

            return JsonRpcResponse.success(id, result);
        }
    }

    /**
     * 将 MCPToolDefinition 转换为 MCPToolSchema，用于 tools/list 响应
     *
     * @param def 工具定义对象，包含名称、描述和参数信息
     * @return 符合 MCP 协议的工具 schema 对象
     */
    private MCPToolSchema toSchema(MCPToolDefinition def) {
        Map<String, MCPToolSchema.PropertyDef> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (def.getParameters() != null) {
            def.getParameters().forEach((name, paramDef) -> {
                properties.put(name, MCPToolSchema.PropertyDef.builder()
                        .type(paramDef.getType())
                        .description(paramDef.getDescription())
                        .enumValues(paramDef.getEnumValues())
                        .build());
                if (paramDef.isRequired()) {
                    required.add(name);
                }
            });
        }

        return MCPToolSchema.builder()
                .name(def.getToolId())
                .description(def.getDescription())
                .inputSchema(MCPToolSchema.InputSchema.builder()
                        .type("object")
                        .properties(properties)
                        .required(required.isEmpty() ? null : required)
                        .build())
                .build();
    }
}

