package com.energyx.simdevice;

import com.energyx.device.CommandMessage;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 下行命令待处理队列（线程安全）。
 *
 * <p>SDK 的 onCommand 回调在 Netty IO 线程触发 → {@link #add}；
 * REPL 线程的 ack/status 通过 {@link #latest} / {@link #remove} 取用。</p>
 */
public final class PendingCommands {

    private final ConcurrentLinkedQueue<CommandMessage> queue = new ConcurrentLinkedQueue<>();

    public void add(CommandMessage command) {
        queue.add(command);
    }

    /** 最新一条待处理命令（队尾），队列空返回 null。 */
    public CommandMessage latest() {
        CommandMessage last = null;
        for (CommandMessage c : queue) {
            last = c;
        }
        return last;
    }

    /** 按 commandId 移除并返回；未找到返回 null。 */
    public CommandMessage remove(String commandId) {
        if (commandId == null) {
            return null;
        }
        for (Iterator<CommandMessage> it = queue.iterator(); it.hasNext(); ) {
            CommandMessage c = it.next();
            if (commandId.equals(c.commandId())) {
                it.remove();
                return c;
            }
        }
        return null;
    }

    public int pendingCount() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
