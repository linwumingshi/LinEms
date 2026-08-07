package com.sanduo.energy.ems.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** TDengine 点序列读写（TAOS-RS RESTful）。子表按电站建 plan_{stationId}，STABLE 为 ems_plan_point。 */
@Slf4j
@Component
public class TdenginePlanWriter {

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
                sb.append("('").append(planDate).append(" ").append(p.time()).append("', '")
                  .append(p.action()).append("', ")
                  .append(p.powerKw()).append(", ")
                  .append(p.socTarget()).append(") ");
            }
            st.execute(sb.toString());
        }
    }

    /** 读取指定计划日期的点序列（按 ts 升序）。 */
    public List<PlanPoint> read(long stationId, LocalDate planDate) throws Exception {
        List<PlanPoint> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ts, action, power_kw, soc FROM plan_" + stationId
                             + " WHERE ts >= '" + planDate + " 00:00:00'"
                             + "   AND ts <  '" + planDate.plusDays(1) + " 00:00:00'"
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
