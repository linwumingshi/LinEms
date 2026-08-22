package com.energyx.broker.config;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * Netty 传输层工厂（阶段 2：原生传输自适应）。
 *
 * <p>
 * 优先级：Epoll（Linux，SO_REUSEPORT/零拷贝/批量唤醒）→ KQueue（macOS，反射，本地仓库 无构件故不引入编译期依赖）→
 * NIO（回退）。探测基于 {@code netty-transport-native-*} 是否在 classpath
 * 且当前平台可用（{@code Epoll.isAvailable()}）；optional 依赖缺失时 自动回退 NIO，Windows 开发机恒为 NIO（功能验证足够）。
 * </p>
 *
 * <p>
 * 生产 Linux 部署：pom 内 optional 的 epoll 依赖改为非 optional（或运行时注入
 * netty-transport-native-epoll:linux-x86_64 jar），本工厂自动启用 epoll；无原生 jar 时 打印告警并回退
 * NIO，不阻断启动。
 * </p>
 */
@Slf4j
public final class TransportFactory {

	private static volatile boolean resolved;

	private static volatile TransportKind kind = TransportKind.NIO;

	/** 传输层类型：Epoll（Linux 原生）/ KQueue（macOS 原生）/ NIO（回退） */
	public enum TransportKind {

		EPOLL, KQUEUE, NIO

	}

	private TransportFactory() {
	}

	public static TransportKind detect() {
		if (resolved) {
			return kind;
		}
		synchronized (TransportFactory.class) {
			if (resolved) {
				return kind;
			}
			try {
				if (Epoll.isAvailable()) {
					kind = TransportKind.EPOLL;
				}
				else if (kqueueAvailable()) {
					kind = TransportKind.KQUEUE;
				}
				else {
					kind = TransportKind.NIO;
					log.warn(
							"[Transport] 无原生传输可用（epoll 未就绪：{}），回退 NIO。"
									+ "生产 Linux 请引入 netty-transport-native-epoll 原生 jar。",
							Epoll.unavailabilityCause() == null ? "n/a" : String.valueOf(Epoll.unavailabilityCause()));
				}
			}
			catch (Throwable t) {
				// optional 依赖缺失：NoClassDefFoundError 等，回退 NIO
				kind = TransportKind.NIO;
				log.warn("[Transport] epoll/kqueue 类不可用（{}），回退 NIO 传输", t.getMessage());
			}
			log.info("[Transport] 传输层选定：{}", kind);
			resolved = true;
			return kind;
		}
	}

	public static boolean isNative() {
		return detect() != TransportKind.NIO;
	}

	/** 创建事件循环组（ioThreads ≤0 时按 2×CPU 默认） */
	public static EventLoopGroup newEventLoopGroup(int ioThreads) {
		// 未显式配置线程数时按 2×CPU 推导（下限 4），兼顾吞吐与上下文切换开销
		int threads = ioThreads > 0 ? ioThreads : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
		return switch (detect()) {
			case EPOLL -> new EpollEventLoopGroup(threads);
			case KQUEUE -> reflectiveGroup(threads);
			default -> new NioEventLoopGroup(threads);
		};
	}

	/** 创建服务端 channel 类（acceptor 用） */
	@SuppressWarnings("unchecked")
	public static Class<? extends ServerChannel> serverChannelClass() {
		// 按探测到的传输类型返回对应服务端 channel 类（acceptor 创建用）
		return switch (detect()) {
			case EPOLL -> EpollServerSocketChannel.class;
			case KQUEUE -> (Class<? extends ServerChannel>) reflectiveChannelClass();
			default -> NioServerSocketChannel.class;
		};
	}

	// ---------------- KQueue 反射（本地仓库无构件，不引编译期依赖） ----------------

	private static boolean kqueueAvailable() {
		try {
			// 反射探测 KQueue 类可用性（本地仓库无构件，故不引入编译期依赖，运行期动态加载）
			Class<?> kq = Class.forName("io.netty.channel.kqueue.KQueue");
			Method isAvailable = kq.getMethod("isAvailable");
			return Boolean.TRUE.equals(isAvailable.invoke(null));
		}
		catch (Throwable t) {
			return false;
		}
	}

	private static EventLoopGroup reflectiveGroup(int threads) {
		try {
			// 反射创建 KQueue 事件循环组（macOS 原生传输）
			Class<?> cls = Class.forName("io.netty.channel.kqueue.KQueueEventLoopGroup");
			return (EventLoopGroup) cls.getConstructor(int.class).newInstance(threads);
		}
		catch (Throwable t) {
			// 反射失败（无构件/平台不支持）回退 NIO，保证跨平台可运行
			log.warn("[Transport] KQueue 反射创建失败（{}），回退 NIO", t.getMessage());
			return new NioEventLoopGroup(threads);
		}
	}

	private static Class<?> reflectiveChannelClass() {
		try {
			// 反射获取 KQueue 服务端 channel 类
			return Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel");
		}
		catch (Throwable t) {
			// 失败回退 NIO 服务端 channel 类
			log.warn("[Transport] KQueue channel 反射失败（{}），回退 NIO", t.getMessage());
			return NioServerSocketChannel.class;
		}
	}

}
