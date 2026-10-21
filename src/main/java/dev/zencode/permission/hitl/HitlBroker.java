package dev.zencode.permission.hitl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * HITL 确认调度器：负责产生「需确认」请求并阻塞等待用户决策。
 * <p>
 * {@link #ask} 在请求线程（Agent 拦截器）阻塞，直到对应 callId 的
 * {@link #resolve} 被调用（由 UI 按键选择触发）后返回决策并使调用放行或拒绝。
 * ask 与 resolve 通过 callId 关联，线程安全。
 * </p>
 * <p>
 * 通过 {@link #setRequestListener} 注册的监听器在 ask 挂起前被调用，
 * 使上层（如 UI）能渲染确认对话框；不注册监听器时仍可阻塞等待，
 * 但用户看不到请求，须由外部主动 resolve。
 * </p>
 */
public final class HitlBroker {
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final long timeoutNanos;
    private volatile Consumer<HitlRequest> requestListener;

    public HitlBroker() {
        this(0);
    }

    /**
     * @param timeoutNanos 等待用户决策的最长阻塞时长；0 或负值表示无限等待
     */
    public HitlBroker(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    /** 注册确认请求监听器；在 ask 挂起前调用。可重复注册，后注册者覆盖。 */
    public void setRequestListener(Consumer<HitlRequest> requestListener) {
        this.requestListener = requestListener;
    }

    /**
     * 发布一条需确认请求并阻塞等待用户决策。
     *
     * @param request 脱敏的确认请求
     * @return 用户决策
     */
    public HitlDecision ask(HitlRequest request) {
        Pending p = new Pending();
        pending.put(request.callId(), p);
        Consumer<HitlRequest> listener = requestListener;
        if (listener != null) {
            try {
                listener.accept(request);
            } catch (RuntimeException ignored) {
                // 通知失败不影响阻塞等待；仍由外部 resolve 决定
            }
        }
        try {
            if (timeoutNanos > 0) {
                if (!p.latch.await(timeoutNanos, TimeUnit.NANOSECONDS)) {
                    return HitlDecision.DENY;
                }
            } else {
                p.latch.await();
            }
            return p.decision;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HitlDecision.DENY;
        } finally {
            pending.remove(request.callId());
        }
    }

    /**
     * 用户对某 callId 的请求做出决策；解除对应 ask 的阻塞。
     * 只生效一次：已决策的请求再次 resolve 返回 false。
     *
     * @return 存在对应挂起请求且本次首次解除成功返回 true
     */
    public boolean resolve(String callId, HitlDecision decision) {
        Pending p = pending.get(callId);
        if (p == null) return false;
        if (!p.resolved.compareAndSet(false, true)) return false;
        p.decision = decision;
        p.latch.countDown();
        return true;
    }

    /** 请求仍在等待首次决策；已解决但尚未清理的请求也返回 false。 */
    public boolean isPending(String callId) {
        Pending request = pending.get(callId);
        return request != null && !request.resolved.get();
    }

    /** 当前挂起的待确认请求数。 */
    public int pendingCount() {
        return pending.size();
    }

    private static final class Pending {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean resolved = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile HitlDecision decision = HitlDecision.DENY;
    }
}
