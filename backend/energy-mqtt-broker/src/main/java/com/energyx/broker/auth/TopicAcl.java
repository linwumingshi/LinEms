package com.energyx.broker.auth;

import com.energyx.broker.util.TopicMatcher;
import com.energyx.common.mqtt.MqttTopicUtil;

/**
 * Topic ACL（防设备伪装，Phase 1 §4.8）：
 * <ul>
 *   <li>设备只能 publish 到 {productKey}/{deviceName}/up/*；</li>
 *   <li>设备只能 subscribe 到 {productKey}/{deviceName}/down/*；</li>
 *   <li>任何其他 topic（含其他设备的上下行）一律拒绝，防止越权读写；</li>
 *   <li>阶段 2（P1-11）：共享订阅 $share/{group}/{filter} 剥前缀后按真实 filter 校验，
 *       设备可用共享组订阅自己的 down topic（多设备接收同一指令时组内负载均衡）。</li>
 * </ul>
 */
public final class TopicAcl {

    /** 设备允许 publish 的上行 type 白名单 */
    private static final String[] UP_TYPES = {"property", "event", "lifecycle", "ack"};

    private TopicAcl() {
    }

    /** 设备 publish 校验：仅限本设备 up/{type} */
    public static boolean canPublish(DeviceCredential cred, String topic) {
        String prefix = cred.getProductKey() + "/" + cred.getDeviceName() + "/up/";
        if (!topic.startsWith(prefix)) {
            return false;
        }
        String type = topic.substring(prefix.length());
        for (String t : UP_TYPES) {
            if (t.equals(type)) {
                return true;
            }
        }
        // 预留扩展 type 不再放开：白名单制
        return false;
    }

    /** 设备订阅校验：仅限本设备 down/* 通配（down/command、down/#），支持 $share 前缀 */
    public static boolean canSubscribe(DeviceCredential cred, String topicFilter) {
        // 共享订阅剥 $share/{group}/ 前缀后按真实 filter 校验（阶段 2 P1-11）
        String filter = TopicMatcher.stripSharePrefix(topicFilter);
        String prefix = cred.getProductKey() + "/" + cred.getDeviceName() + "/down/";
        if (filter.equals(prefix.substring(0, prefix.length() - 1))) {
            return false; // 裸 down 不含 /，非法
        }
        return filter.startsWith(prefix);
    }

    /** 设备标准下行指令 topic（平台→设备） */
    public static String downCommandTopic(DeviceCredential cred) {
        return MqttTopicUtil.downCommandTopic(cred.getProductKey(), cred.getDeviceName());
    }
}
