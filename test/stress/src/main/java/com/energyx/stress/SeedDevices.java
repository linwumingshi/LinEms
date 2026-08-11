package com.energyx.stress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 设备造数工具：批量写入 iot_device + iot_device_credential（es_device 库）。
 *
 * <p>写入后 Broker 认证链（Redis 缓存 + MySQL 回源）与 access 设备上下文即可识别这些设备，
 * 压测工具才能用同一批 productKey/deviceName/secret 通过 HMAC 认证真实接入。</p>
 *
 * <ul>
 *   <li>INSERT ... ON DUPLICATE KEY UPDATE 幂等且自愈：重复执行只补缺，不报错；
 *       已逻辑删除的设备行会复活（deleted=0/status=2）、被吊销凭据重新激活（auth_status=1），
 *       避免「删 A 设备后重加 A」残留吊销凭据导致连不上（uk_cred_device 无 deleted 列）；</li>
 *   <li>device_secret 由 {@link Secrets#deriveSecret} 确定性派生，压测工具可复现；</li>
 *   <li>device.status=2（已激活离线）满足 Broker「仅 2/3 允许接入」的状态校验；</li>
 *   <li>--start-index N 从序号 N 开始造数（默认 1）：多产品造数时避开已占用设备名/号段
 *       （如默认 PCS 已占 sim-dev-000001），并配合 --station 把设备挂到电站；</li>
 *   <li>分批 executeBatch（500 行/批），10 万级造数秒级完成。</li>
 * </ul>
 */
public final class SeedDevices {

    private static final Logger log = LoggerFactory.getLogger(SeedDevices.class);
    private static final int BATCH_SIZE = 500;

    /** 设备 ID 基数（避开平台雪花号段，压测造数互不冲突）。 */
    static final long DEVICE_ID_BASE = 8_000_000_000_000_000_000L;

    private SeedDevices() {
    }

    public static int run(Args args) throws SQLException {
        if (args.startIndex < 1) {
            throw new IllegalArgumentException("start-index 必须 ≥ 1，当前: " + args.startIndex);
        }
        try (Connection conn = DriverManager.getConnection(args.jdbcUrl, args.user, args.password)) {
            checkProductExists(conn, args.productKey);
            int inserted = 0;
            try (PreparedStatement dev = conn.prepareStatement(
                    "INSERT INTO iot_device (device_id, tenant_id, enterprise_id, station_id, "
                            + "product_key, device_name, device_type, parent_id, path, level, sort, status, "
                            + "protocol, deleted) VALUES (?,?,?,?,?,?,?,0,'/',1,0,2,'MQTT',0) "
                            + "ON DUPLICATE KEY UPDATE deleted = 0, status = 2");
                 PreparedStatement cred = conn.prepareStatement(
                         "INSERT INTO iot_device_credential (device_id, tenant_id, device_secret, "
                                 + "auth_status, fail_count) VALUES (?,?,?,1,0) "
                                 + "ON DUPLICATE KEY UPDATE device_secret = VALUES(device_secret), "
                                 + "auth_status = 1, fail_count = 0")) {

                long baseId = args.deviceIdBase;
                int maxIndex = args.startIndex + args.count - 1;
                for (int i = args.startIndex; i <= maxIndex; i++) {
                    long deviceId = baseId + i;
                    String name = Secrets.deviceName(i, maxIndex);
                    dev.setLong(1, deviceId);
                    dev.setLong(2, args.tenantId);
                    dev.setObject(3, args.enterpriseId == null ? null : args.enterpriseId);
                    dev.setObject(4, args.stationId == null ? null : args.stationId);
                    dev.setString(5, args.productKey);
                    dev.setString(6, name);
                    dev.setString(7, args.deviceType);
                    dev.addBatch();

                    cred.setLong(1, deviceId);
                    cred.setLong(2, args.tenantId);
                    cred.setString(3, Secrets.deriveSecret(args.secretBase, i));
                    cred.addBatch();

                    if (i % BATCH_SIZE == 0) {
                        inserted += flush(dev, cred);
                        log.info("[Seed] 已写入 {} / {} 台", i, args.count);
                    }
                }
                inserted += flush(dev, cred);
            }
            log.info("[Seed] 完成：共 {} 台设备（含重复跳过）", args.count);
            return inserted;
        }
    }

    private static int flush(PreparedStatement dev, PreparedStatement cred) throws SQLException {
        int n = 0;
        int[] devRows = dev.executeBatch();
        for (int r : devRows) {
            if (r > 0) {
                n++;
            }
        }
        cred.executeBatch();
        return n;
    }

    private static void checkProductExists(Connection conn, String productKey) throws SQLException {
        // iot_product 位于 product 域逻辑库；同实例下 root 可直接跨库引用
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT product_id FROM es_product.iot_product WHERE product_key = ? LIMIT 1")) {
            ps.setString(1, productKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("产品不存在: product_key=" + productKey
                            + "（请先执行 sql/mysql/20_product.sql 种子数据）");
                }
            }
        }
    }

    /** 造数参数。 */
    public static final class Args {
        String jdbcUrl;
        String user;
        String password;
        long tenantId = 1L;
        Long enterpriseId;
        Long stationId;
        String productKey = "snd_ess_pcs";
        String deviceType = "PCS";
        int count = 1000;
        int startIndex = 1;
        String secretBase = "sanduo-stress";
        long deviceIdBase = DEVICE_ID_BASE;

        public Args(String jdbcUrl, String user, String password) {
            this.jdbcUrl = jdbcUrl;
            this.user = user;
            this.password = password;
        }
    }
}
