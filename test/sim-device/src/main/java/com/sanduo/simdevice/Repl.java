package com.sanduo.simdevice;

import com.sanduo.device.CommandMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 交互式命令循环：读 stdin → 解析 → 调用 Connector / PendingCommands → 打印。
 * 不直接触碰 MQTT。解析逻辑抽到 {@link #parse}（可单测）。
 */
public final class Repl {

    /** 固定属性字段集（与 test/stress ThroughputLoad 一致）。 */
    private static final String[] FIELDS = {"soc", "voltage", "current", "power", "temp", "runMode"};

    private final Connector connector;
    private final PendingCommands pending;
    private final BufferedReader in;
    private final PrintStream out;
    private final Random random = new Random();
    private final Object printLock = new Object();

    public Repl(Connector connector, PendingCommands pending, InputStream in, PrintStream out) {
        this.connector = connector;
        this.pending = pending;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    public void run() {
        while (true) {
            String line = readLine();
            if (line == null) {
                break; // EOF
            }
            ParsedCommand cmd = parse(line);
            if (cmd.message() != null) {
                println(cmd.message());
                continue;
            }
            switch (cmd.verb()) {
                case "connect" -> println(connector.connect());
                case "disconnect" -> println(connector.disconnect());
                case "reconnect" -> println(connector.reconnect());
                case "status" -> println(connector.status());
                case "report" -> execReport(cmd.args());
                case "event" -> execEvent(cmd.args());
                case "lifecycle" -> execLifecycle(cmd.args());
                case "ack" -> execAck(cmd.args());
                case "autoack" -> {
                    connector.setAutoAck(cmd.args().get(0).equals("on"));
                    println("自动回 ACK: " + cmd.args().get(0));
                }
                case "help" -> println(usage());
                case "quit" -> {
                    connector.disconnect();
                    return;
                }
                default -> println("未知命令（help 查看命令表）");
            }
        }
    }

    private void execReport(List<String> args) {
        Map<String, Object> props = args.isEmpty() ? randomProps() : parseKV(args, "属性");
        if (props == null) {
            return; // parseKV 已打印错误
        }
        try {
            connector.publishProperty(props);
            println("已上报属性: " + props);
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    private void execEvent(List<String> args) {
        String name = args.get(0);
        int severity = 1;
        String code = null;
        List<String> rest = new ArrayList<>(args.subList(1, args.size()));
        if (!rest.isEmpty() && isInteger(rest.get(0))) {
            severity = Integer.parseInt(rest.remove(0));
        }
        if (!rest.isEmpty() && !rest.get(0).contains("=")) {
            code = rest.remove(0);
        }
        Map<String, Object> data = parseKV(rest, "事件");
        if (data == null) {
            return;
        }
        try {
            connector.publishEvent(name, severity, code, data);
            println("已上报事件 " + name + " severity=" + severity
                    + (code != null ? " code=" + code : "") + " data=" + data);
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    private void execLifecycle(List<String> args) {
        String eventType = args.get(0);
        String ip = args.size() >= 2 ? args.get(1) : null;
        try {
            connector.publishLifecycle(eventType, ip);
            println("已上报上下线 " + eventType + (ip != null ? " ip=" + ip : ""));
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    private void execAck(List<String> args) {
        String status = args.size() >= 2 ? args.get(1).toUpperCase(Locale.ROOT) : "SUCCESS";
        if (!status.equals("SUCCESS") && !status.equals("FAILED")) {
            println("status 只能是 SUCCESS 或 FAILED");
            return;
        }
        String commandId = args.isEmpty() ? null : args.get(0);
        if (commandId == null) {
            CommandMessage latest = pending.latest();
            commandId = latest == null ? null : latest.commandId();
        }
        if (commandId == null) {
            println("没有待处理的命令（status 查看，或收到命令后 ack）");
            return;
        }
        try {
            connector.ackCommand(commandId, status);
            pending.remove(commandId);
            println("已回 ACK " + commandId + " → " + status);
        } catch (IllegalStateException e) {
            println(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 参数辅助
    // ------------------------------------------------------------------

    /** 随机一组 6 字段属性（与 stress ThroughputLoad 分布一致）。 */
    private Map<String, Object> randomProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("soc", 40 + random.nextInt(60));
        props.put("voltage", 200 + random.nextInt(50));
        props.put("current", random.nextInt(40));
        props.put("power", 500 + random.nextInt(3000));
        props.put("temp", 25 + random.nextInt(20));
        props.put("runMode", random.nextInt(3));
        return props;
    }

    /** 解析 k=v 列表；任一参数缺 '=' 打印用法并返回 null。数值转 Long/Double，其余留字符串。 */
    private Map<String, Object> parseKV(List<String> tokens, String kind) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq <= 0) {
                println("参数需为 k=v 形式（" + kind + "）: " + t);
                return null;
            }
            map.put(t.substring(0, eq), parseValue(t.substring(eq + 1)));
        }
        return map;
    }

    /** 数值字符串转 Long/Double，否则原样字符串。 */
    private Object parseValue(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignore) {
            // fall through
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignore) {
            // fall through
        }
        return v;
    }

    private static boolean isInteger(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // IO（锁保护，避免与 IO 线程的下行命令横幅乱行）
    // ------------------------------------------------------------------

    private String readLine() {
        printPrompt();
        try {
            return in.readLine();
        } catch (IOException e) {
            println("读取输入失败: " + e.getMessage());
            return null;
        }
    }

    private void printPrompt() {
        synchronized (printLock) {
            out.print("sim-dev> ");
            out.flush();
        }
    }

    private void println(String s) {
        synchronized (printLock) {
            out.println(s);
        }
    }

    /** IO 线程回调：打印下行命令横幅并重绘 prompt。 */
    void notifyCommand(CommandMessage command) {
        synchronized (printLock) {
            out.print("\r");
            out.println("↓ 收到下行命令: " + command);
            if (connector.autoAck()) {
                out.println("  （autoack on，已自动回 SUCCESS）");
            }
            out.print("sim-dev> ");
            out.flush();
        }
    }

    /** IO 线程回调：SDK 异常横幅。 */
    void notifyError(Throwable cause) {
        synchronized (printLock) {
            out.print("\r");
            out.println("[SDK 异常] " + (cause.getMessage() == null ? cause : cause.getMessage()));
            out.print("sim-dev> ");
            out.flush();
        }
    }

    // ------------------------------------------------------------------
    // 解析（包可见，单测目标）
    // ------------------------------------------------------------------

    /** 解析一行命令为 ParsedCommand。空行→noop；未知/非法→带 message 的错误命令。 */
    static ParsedCommand parse(String line) {
        if (line == null) {
            return ParsedCommand.quit();
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.noop();
        }
        String[] parts = trimmed.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        List<String> rest = new ArrayList<>(Arrays.asList(parts).subList(1, parts.length));
        switch (verb) {
            case "connect", "disconnect", "reconnect", "status" -> {
                if (!rest.isEmpty()) {
                    return ParsedCommand.error("用法: " + verb);
                }
                return ParsedCommand.of(verb);
            }
            case "report" -> {
                return ParsedCommand.of("report", rest);
            }
            case "event" -> {
                if (rest.isEmpty()) {
                    return ParsedCommand.error("用法: event <name> [severity] [code] [k=v...]");
                }
                return ParsedCommand.of("event", rest);
            }
            case "lifecycle" -> {
                if (rest.isEmpty()
                        || (!rest.get(0).equals("online") && !rest.get(0).equals("offline"))) {
                    return ParsedCommand.error("用法: lifecycle online|offline [ip]");
                }
                return ParsedCommand.of("lifecycle", rest);
            }
            case "ack" -> {
                if (rest.size() > 2) {
                    return ParsedCommand.error("用法: ack [commandId] [SUCCESS|FAILED]");
                }
                return ParsedCommand.of("ack", rest);
            }
            case "autoack" -> {
                if (rest.size() != 1
                        || (!rest.get(0).equals("on") && !rest.get(0).equals("off"))) {
                    return ParsedCommand.error("用法: autoack on|off");
                }
                return ParsedCommand.of("autoack", rest);
            }
            case "help" -> {
                return ParsedCommand.help();
            }
            case "quit", "exit" -> {
                return ParsedCommand.quit();
            }
            default -> {
                return ParsedCommand.unknown("未知命令: " + verb + "（help 查看命令表）");
            }
        }
    }

    /** 命令表。 */
    public static String usage() {
        return """
                命令:
                  connect / disconnect / reconnect   连接管理
                  report [k=v ...]                   上报属性（无参数 = 随机一组）
                  event <name> [severity] [code] [k=v...]   上报事件
                  lifecycle online|offline [ip]       上报上下线
                  status                              查看连接状态与待处理命令
                  ack [commandId] [SUCCESS|FAILED]    回指令 ACK（缺省取最新一条 / SUCCESS）
                  autoack on|off                      切换自动回 ACK
                  help                                显示本命令表
                  quit                                断开并退出""";
    }

    /** 解析结果：message 非空表示错误/未知命令（verb 为空）；verb="help"/"quit" 供执行分支识别。 */
    record ParsedCommand(String verb, List<String> args, String message) {

        static ParsedCommand of(String verb) {
            return new ParsedCommand(verb, List.of(), null);
        }

        static ParsedCommand of(String verb, List<String> args) {
            return new ParsedCommand(verb, List.copyOf(args), null);
        }

        static ParsedCommand noop() {
            return new ParsedCommand("", List.of(), null);
        }

        static ParsedCommand help() {
            return new ParsedCommand("help", List.of(), null);
        }

        static ParsedCommand quit() {
            return new ParsedCommand("quit", List.of(), null);
        }

        static ParsedCommand error(String message) {
            return new ParsedCommand("", List.of(), message);
        }

        static ParsedCommand unknown(String message) {
            return new ParsedCommand("", List.of(), message);
        }
    }
}
