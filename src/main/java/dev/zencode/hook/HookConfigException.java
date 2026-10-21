package dev.zencode.hook;

/** Hook 配置错误，消息中携带规则定位（文件与序号）。 */
public class HookConfigException extends RuntimeException {

    public HookConfigException(String message) {
        super(message);
    }

    public HookConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
