package dev.zencode.prompt;

import java.nio.file.Path;
import java.util.List;

/** 测试工厂：构造默认 RequestAssembler。 */
final class AssemblerFactory {

    private AssemblerFactory() {}

    static RequestAssembler assembler() {
        return new RequestAssembler(Path.of("."), "claude-test",
                SystemPromptBuilder.defaults().build(), List.of());
    }
}