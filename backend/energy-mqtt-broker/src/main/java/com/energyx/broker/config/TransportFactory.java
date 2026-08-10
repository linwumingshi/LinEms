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
		return switch (detect()) {
			case EPOLL -> EpollServerSocketChannel.class;
			case KQUEUE -> (Class<? extends ServerChannel>) reflectiveChannelClass();
			default -> NioServerSocketChannel.class;
		};
	}

	// ---------------- KQueue 反射（本地仓库无构件，不引编译期依赖） ----------------

	private static boolean kqueueAvailable() {
		try {
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
			Class<?> cls = Class.forName("io.netty.channel.kqueue.KQueueEventLoopGroup");
			return (EventLoopGroup) cls.getConstructor(int.class).newInstance(threads);
		}
		catch (Throwable t) {
			log.warn("[Transport] KQueue 反射创建失败（{}），回退 NIO", t.getMessage());
			return new NioEventLoopGroup(threads);
		}
	}

	private static Class<?> reflectiveChannelClass() {
		try {
			return Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel");
		}
		catch (Throwable t) {
			log.warn("[Transport] KQueue channel 反射失败（{}），回退 NIO", t.getMessage());
			return NioServerSocketChannel.class;
		}
	}

}
