# -*- coding: utf-8 -*-
"""Broker 双阈值准入 + readiness 冒烟实测脚本（T8 Step 3 验收，修正版）。

场景：broker 以 --energyx.broker.max-connections=5 启动
  softLimit = (long)(5*0.9) = 4   （软拒 / readiness DOWN 判据：connectionCounter > 4）
  hardLimit = (long)(5*1.05)= 5   （硬拒判据：channelActive increment 后 > 5，即第 6 个连接）
判定统一为 >（等于阈值视为可接），见 BrokerLoadHealthIndicator.isConnectionOverloaded。

修正要点（v2）：
  旧版把"第 5 条 CONNECT 被软拒 close 后"再开第 6 条当硬拒测试——此时计数已回落 4，
  第 6 条 increment 到 5 不超 hardLimit=5，根本不触发硬拒（误报失败）。
  正确做法：先 4 raw 占位 -> 第 5 条发 CONNECT 验证软拒 0x03（close 后计数回落 4）
  -> 补 1 条 raw 常驻把计数顶到 5（readiness 应 DOWN）-> 第 6 条 raw 验证硬拒 TCP close。
验证口径：MQTT 回包 + /internal/broker/stats 计数 + readiness/liveness 三向实证。
"""
import json
import socket
import struct
import time
import urllib.request

BROKER_HOST = "127.0.0.1"
BROKER_MQTT_PORT = 18831
HEALTH_PORT = 8082


def build_connect(client_id: str) -> bytes:
    """构造 MQTT 3.1.1 CONNECT 报文（cleanSession=1, keepalive=60）。"""
    proto = b"\x00\x04MQTT"
    level = bytes([4])
    flags = bytes([0x02])  # cleanSession=1
    keepalive = struct.pack(">H", 60)
    var_header = proto + level + flags + keepalive
    cid = client_id.encode("utf-8")
    payload = struct.pack(">H", len(cid)) + cid
    remaining = var_header + payload
    assert len(remaining) < 128, "剩余长度需单字节变长编码"
    return bytes([0x10, len(remaining)]) + remaining


def read_exact(sock: socket.socket, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            break
        data += chunk
    return data


def read_packet(sock: socket.socket) -> bytes:
    """读一个 MQTT 报文（支持 1-4 字节剩余长度变长编码）。

    返回完整报文（固定头 + 剩余长度字节 + 正文）；长度字节必须拼回，
    否则解析方按固定偏移读 returnCode 会错位。
    """
    first = read_exact(sock, 1)
    if not first:
        return b""
    mult = 1
    rem = 0
    len_bytes = bytearray()
    while True:
        b = read_exact(sock, 1)
        if not b:
            return b""
        len_bytes.append(b[0])
        rem += (b[0] & 0x7F) * mult
        if not (b[0] & 0x80):
            break
        mult *= 128
    body = read_exact(sock, rem)
    return first + bytes(len_bytes) + body


def open_raw_conn() -> socket.socket:
    """建立一条纯 TCP 连接（不发任何数据，仅占 channelActive 计数）。"""
    return socket.create_connection((BROKER_HOST, BROKER_MQTT_PORT), timeout=5)


def http_get(path: str) -> dict:
    req = urllib.request.Request(f"http://127.0.0.1:{HEALTH_PORT}{path}")
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get_maybe_503(path: str) -> dict:
    """readiness DOWN 时 /actuator/health 返回 503，仍需解析 body。"""
    req = urllib.request.Request(f"http://127.0.0.1:{HEALTH_PORT}{path}")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8"))


def fetch_stats() -> dict:
    data = http_get("/internal/broker/stats")
    return data.get("data", {})


def main():
    results = {}
    holders = []

    def close_all():
        for s in holders:
            try:
                s.close()
            except Exception:
                pass

    try:
        # 基准：stats 计数清零基线
        base = fetch_stats()
        results["base_admissionRedirect"] = base.get("admissionRedirect")
        results["base_rejectedConnections"] = base.get("rejectedConnections")
        results["base_acceptedConnections"] = base.get("acceptedConnections")

        # ---- 1. 4 条 raw TCP 占位（channelActive 计数 1->4）----
        for _ in range(4):
            holders.append(open_raw_conn())
        time.sleep(0.8)
        ready = http_get_maybe_503("/actuator/health/readiness")
        results["after_4_raw_readiness"] = ready.get("status")
        results["after_4_raw_brokerLoad"] = ready.get("components", {}).get("brokerLoad", {}).get("status")
        print(f"[1] 4 raw 后 readiness={ready.get('status')} (期待 UP，计数 4 不超 softLimit 4)")

        # ---- 2. 第 5 条发合法 CONNECT -> 期待 CONNACK 0x03（计数 5 > softLimit 4）----
        s5 = socket.create_connection((BROKER_HOST, BROKER_MQTT_PORT), timeout=5)
        holders.append(s5)
        s5.sendall(build_connect("dev-soft-reject-smoke"))
        pkt = read_packet(s5)
        is_connack = len(pkt) >= 4 and (pkt[0] & 0xF0) == 0x20
        ret_code = pkt[3] if is_connack else None
        results["5th_connack_received"] = is_connack
        results["5th_return_code"] = ret_code  # 3 = 0x03 SERVER_UNAVAILABLE
        results["5th_pkt_raw"] = pkt.hex(" ") if pkt else "(empty)"
        print(f"[2] 第 5 条 CONNECT -> CONNACK={is_connack} returnCode={ret_code} pkt={results['5th_pkt_raw']} (期待 True / 3=0x03)")
        # 软拒后 broker 会 close，等计数回落
        time.sleep(1.0)
        stats_after_soft = fetch_stats()
        results["admissionRedirect_after_soft"] = stats_after_soft.get("admissionRedirect")
        print(f"[2+] 软拒后 stats.admissionRedirect={stats_after_soft.get('admissionRedirect')} (期待 基线+1)")

        # ---- 3. 补 1 条 raw 常驻，把计数顶回 5 -> readiness 应 DOWN（5 > softLimit 4）----
        holders.append(open_raw_conn())
        time.sleep(0.8)
        ready5 = http_get_maybe_503("/actuator/health/readiness")
        results["readiness_at_5"] = ready5.get("status")
        results["brokerLoad_at_5"] = ready5.get("components", {}).get("brokerLoad", {}).get("details")
        print(f"[3] 计数=5 时 readiness={ready5.get('status')} (期待 DOWN)")

        # ---- 4. 第 6 条 raw -> channelActive 计数 6 > hardLimit 5 -> 硬拒 TCP close ----
        s6 = open_raw_conn()
        holders.append(s6)
        time.sleep(0.8)
        closed = False
        try:
            s6.settimeout(2)
            data = s6.recv(1)
            closed = data == b""
        except socket.timeout:
            closed = False
        except OSError:
            closed = True
        results["6th_tcp_closed"] = closed
        print(f"[4] 第 6 条 raw 被服务端关闭={closed} (期待 True，计数 6 > hardLimit 5)")
        stats_after_hard = fetch_stats()
        results["rejectedConnections_after_hard"] = stats_after_hard.get("rejectedConnections")
        print(f"[4+] 硬拒后 stats.rejectedConnections={stats_after_hard.get('rejectedConnections')} (期待 基线+1)")

        # ---- 5. 5 条存活时 readiness DOWN / liveness 恒 UP ----
        live = http_get("/actuator/health/liveness")
        results["liveness_at_overload"] = live.get("status")
        print(f"[5] 过载态 liveness={live.get('status')} (期待恒 UP，绝不可随过载 DOWN)")

        # ---- 6. 全部关闭后计数应回落，readiness 恢复 UP ----
        close_all()
        holders.clear()
        time.sleep(1.0)
        ready_final = http_get_maybe_503("/actuator/health/readiness")
        results["readiness_after_release"] = ready_final.get("status")
        results["brokerLoad_final"] = ready_final.get("components", {}).get("brokerLoad", {}).get("details")
        print(f"[6] 全部释放后 readiness={ready_final.get('status')} (期待恢复 UP)")
    finally:
        close_all()

    print("\n==== 汇总 ====")
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
