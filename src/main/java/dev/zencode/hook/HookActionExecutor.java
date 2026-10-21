package dev.zencode.hook;

/** 动作执行器接口：对给定动作执行并返回输出文本。 */
public interface HookActionExecutor {

    /** 执行动作，返回输出文本；失败返回错误描述文本，不抛异常。 */
    String execute(Action action, HookContext context);
}
