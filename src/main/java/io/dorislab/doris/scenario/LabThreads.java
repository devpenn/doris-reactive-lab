package io.dorislab.doris.scenario;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 无 Vertx 上下文的普通线程池：HR 客户端在 Vertx worker/eventloop 以外的裸线程上可用
 * （HR000068 只在有 Vertx 上下文的非 eventloop 线程触发；run2 的裸 JUnit 线程与 run9 的裸线程实证）。 */
public final class LabThreads {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ExecutorService PLAIN = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lab-plain-" + COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private LabThreads() {
    }

    public static ExecutorService plain() {
        return PLAIN;
    }
}
