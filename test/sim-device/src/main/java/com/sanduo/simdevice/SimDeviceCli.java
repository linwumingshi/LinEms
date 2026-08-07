package com.sanduo.simdevice;

import com.sanduo.device.DeviceIdentity;

import java.util.Arrays;

/**
 * sim-device 入口：解析 CLI 参数 → 构造 DeviceIdentity/Connector/Repl →
 * 自动连接 → 进入交互式 REPL。
 */
public final class SimDeviceCli {

    private SimDeviceCli() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            System.out.println(CliArgs.usage());
            return;
        }
        CliArgs cli;
        try {
            cli = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println();
            System.err.println(CliArgs.usage());
            System.exit(2);
            return;
        }

        DeviceIdentity identity = new DeviceIdentity(cli.product(), cli.deviceName(), cli.deviceSecret());
        PendingCommands pending = new PendingCommands();
        Connector connector = new Connector(identity, cli.host(), cli.port(), pending);
        Repl repl = new Repl(connector, pending, System.in, System.out);
        connector.setOnCommandArrived(repl::notifyCommand);
        connector.setOnError(repl::notifyError);
        connector.setAutoAck(cli.autoAck());

        System.out.println("EnergyX 平台交互式模拟器");
        System.out.println("  clientId: " + identity.clientId());
        System.out.println("  broker:   " + cli.host() + ":" + cli.port());
        System.out.println("  autoack:  " + (cli.autoAck() ? "on" : "off"));
        System.out.println("输入 help 查看命令表");
        System.out.println(connector.connect());

        repl.run();
        System.out.println("已退出");
    }
}
