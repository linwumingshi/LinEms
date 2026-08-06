package com.sanduo.energy.broker.stats;

import com.sanduo.energy.broker.config.BrokerProperties;
import com.sanduo.energy.common.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broker 运维端点（管理端口 8082，供控制台/监控拉取）。
 */
@RestController
@RequestMapping("/internal/broker")
public class BrokerStatsController {

    private final BrokerStats stats;
    private final BrokerProperties properties;

    public BrokerStatsController(BrokerStats stats, BrokerProperties properties) {
        this.stats = stats;
        this.properties = properties;
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> data = new LinkedHashMap<>(stats.snapshot());
        data.put("nodeId", properties.getNodeId());
        data.put("mqttPort", properties.getPort());
        data.put("maxConnections", properties.getMaxConnections());
        data.put("uptimeMillis", System.currentTimeMillis() - startAtMillis);
        return Result.ok(data);
    }

    private static final long startAtMillis = System.currentTimeMillis();
}
