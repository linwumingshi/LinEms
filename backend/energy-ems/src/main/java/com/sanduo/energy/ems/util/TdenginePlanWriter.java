package com.sanduo.energy.ems.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** TDengine 点序列读写（TAOS-RS RESTful）。子表按电站建 plan_{stationId}，STABLE 为 ems_plan_point。 */
@Slf4j
@Component
public class TdenginePlanWriter {

    /** 写时间戳用 JVM 默认时区换算 epoch 毫秒：TDengine 把裸 datetime 字符串按 UTC 解析（会 +8 偏移），
     *  epoch 毫秒 + 读侧 getTime(ts).toLocalTime()（同样按 JVM 时区）可无偏移往返 */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Value("${sanduo.taos.jdbc-url:jdbc:TAOS-RS://127.0.0.1:6041/iot_ems}")
    private String jdbcUrl;

    @Value("${sanduo.taos.username:root}")
    private String username;

    @Value("${sanduo.taos.password:taosdata}")
    private String password;

    /** 写入计划点序列（幂等建库/建 STABLE）。 */
    public void write(long stationId, LocalDate planDate, List<PlanPoint> points) throws Exception {
        if (points.isEmpty()) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS iot_ems");
            st.execute("USE iot_ems");
            st.execute("CREATE STABLE IF NOT EXISTS ems_plan_point "
                    + "(ts TIMESTAMP, action VARCHAR(16), power_kw DOUBLE, soc DOUBLE) "
                    + "TAGS (station_id BIGINT)");
            // INSERT ... USING 自动建子表并写 station_id tag（对齐 tsdb TdengineSqlBuilder 模式）
            String table = "plan_" + stationId;
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table)
                    .append(" USING ems_plan_point TAGS (").append(stationId).append(") ")
                    .append("(ts, action, power_kw, soc) VALUES ");
            for (PlanPoint p : points) {
                long ts = p.time().atDate(planDate).atZone(ZONE).toInstant().toEpochMilli();
                sb.append("(").append(ts).append(", '")
                  .append(p.action()).append("', ")
                  .append(p.powerKw()).append(", ")
                  .append(p.socTarget()).append(") ");
            }
            st.execute(sb.toString());
        }
    }

    /** 读取指定计划日期的点序列（按 ts 升序）。 */
    public List<PlanPoint> read(long stationId, LocalDate planDate) throws Exception {
        // 与 write 一致：日期边界按 JVM 时区换算 epoch 毫秒，避免 TDengine 把裸 datetime 当 UTC 解析造成 +8 偏移
        long start = planDate.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = planDate.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<PlanPoint> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ts, action, power_kw, soc FROM plan_" + stationId
                             + " WHERE ts >= " + start
                             + "   AND ts <  " + end
                             + " ORDER BY ts")) {
            while (rs.next()) {
                java.sql.Time tm = rs.getTime("ts");
                out.add(new PlanPoint(tm.toLocalTime(), rs.getString("action"),
                        rs.getDouble("power_kw"), rs.getDouble("soc")));
            }
        }
        return out;
    }
}
