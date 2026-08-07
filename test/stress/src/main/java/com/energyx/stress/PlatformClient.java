package com.energyx.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台网关 REST 客户端（java.net.http，零第三方依赖）。
 *
 * <p>对接 energy-command 控制链路：</p>
 * <ul>
 *   <li>POST /api/command  创建指令 → 返回 commandId；</li>
 *   <li>GET  /api/command/{id} 查询状态 → 返回 stateName。</li>
 * </ul>
 *
 * <p>统一解包后端 {@code Result<T>}（code=0 成功，否则抛 IOException）。</p>
 */
public final class PlatformClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final String gatewayBase;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public PlatformClient(String gatewayBase) {
        this.gatewayBase = gatewayBase.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /** 创建指令，返回 commandId。 */
    public String createCommand(String productKey, String deviceName, String command,
                                Map<String, Object> params, int timeoutMs) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productKey", productKey);
        body.put("deviceName", deviceName);
        body.put("command", command);
        body.put("params", params == null ? Map.of() : params);
        body.put("commandType", 2);
        body.put("timeoutMs", timeoutMs);
        body.put("maxRetry", 1);
        body.put("createBy", 0);

        HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayBase + "/api/command"))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return parseData(resp).path("commandId").asText("");
    }

    /** 查询指令状态，返回 stateName（SUCCESS/FAILED/TIMEOUT/...）。 */
    public String getState(String commandId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayBase + "/api/command/" + commandId))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return parseData(resp).path("stateName").asText("");
    }

    private JsonNode parseData(HttpResponse<String> resp) throws IOException {
        if (resp.statusCode() >= 500) {
            throw new IOException("网关返回状态码 " + resp.statusCode());
        }
        JsonNode root = json.readTree(resp.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IOException("业务错误 code=" + root.path("code").asInt(-1)
                    + " msg=" + root.path("message").asText(""));
        }
        return root.path("data");
    }
}
