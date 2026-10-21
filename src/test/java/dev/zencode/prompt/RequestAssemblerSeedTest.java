package dev.zencode.prompt;

import dev.zencode.chat.Conversation;
import dev.zencode.chat.ConversationTurn;
import dev.zencode.memory.InstructionLoader;
import dev.zencode.memory.MemoryConfig;
import dev.zencode.memory.MemoryStore;
import dev.zencode.provider.ProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestAssembler 三通道 seed 测试：环境快照 → 指令 → 记忆。
 */
class RequestAssemblerSeedTest {

    @TempDir
    Path tempDir;

    @Test
    void seedInjectsThreeChannelsInOrder() {
        // 准备指令文件
        Path userHome = tempDir.resolve("userhome");
        InstructionLoader loader = new InstructionLoader(tempDir, userHome, MemoryConfig.defaults());
        Path userZencode = userHome.resolve(".zencode");
        try {
            Files.createDirectories(userZencode);
            Files.writeString(userHome.resolve(".zencode/ZENCODE.md"), "用户级指令");
        } catch (Exception e) {
            fail("写入指令文件失败");
        }

        // 准备记忆
        MemoryStore store = new MemoryStore(tempDir, userHome, MemoryConfig.defaults());
        store.write(MemoryStore.Scope.PROJECT, "项目知识：Java 17");

        // 构造带三通道的 assembler
        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of(), loader, store);
        Conversation conversation = new Conversation();

        // 第一次 assemble，应注入 3 个 seed 轮次
        ProviderRequest first = assembler.assemble(conversation, 1, false, "任务");
        assertEquals(3, first.history().size(), "首轮 history 应含 3 个 seed 轮次");
        assertTrue(first.history().get(0).userMessage().text().contains("环境信息"), "第一通道应为环境");
        assertTrue(first.history().get(1).userMessage().text().contains("用户级指令"), "第二通道应为指令");
        assertTrue(first.history().get(2).userMessage().text().contains("项目知识"), "第三通道应为记忆");
        assertTrue(assembler.isSeeded(conversation));
    }

    @Test
    void secondAssembleDoesNotReInject() {
        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        store.write(MemoryStore.Scope.PROJECT, "记忆内容");

        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of(), loader, store);
        Conversation conversation = new Conversation();

        assembler.assemble(conversation, 1, false, "任务1");
        assertEquals(2, conversation.size(), "指令文件不存在所以只注入环境+记忆");

        // 第二次 assemble 不应重复注入
        ProviderRequest second = assembler.assemble(conversation, 2, false, null);
        assertEquals(2, second.history().size(), "第二次 assemble 历史不应增长");
        assertTrue(assembler.isSeeded(conversation));
    }

    @Test
    void emptyLoaderAndStoreSkipChannels() {
        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of(), null, null);
        Conversation conversation = new Conversation();

        ProviderRequest first = assembler.assemble(conversation, 1, false, "任务");
        assertEquals(1, first.history().size(), "无指令和记忆时只注入环境");
    }

    @Test
    void emptyInstructionFileSkipsInstructionChannel() {
        InstructionLoader loader = new InstructionLoader(tempDir, tempDir, MemoryConfig.defaults());
        MemoryStore store = new MemoryStore(tempDir, tempDir, MemoryConfig.defaults());
        store.write(MemoryStore.Scope.PROJECT, "记忆内容");

        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of(), loader, store);
        Conversation conversation = new Conversation();

        ProviderRequest first = assembler.assemble(conversation, 1, false, "任务");
        assertEquals(2, first.history().size(), "指令文件不存在，只注入环境+记忆");
        assertTrue(first.history().get(0).userMessage().text().contains("环境信息"));
        assertTrue(first.history().get(1).userMessage().text().contains("记忆内容"));
    }

    @Test
    void emptyMemoryStoreSkipsMemoryChannel() {
        Path userHome = tempDir.resolve("userhome");
        InstructionLoader loader = new InstructionLoader(tempDir, userHome, MemoryConfig.defaults());
        try {
            Files.createDirectories(userHome.resolve(".zencode"));
            Files.writeString(userHome.resolve(".zencode/ZENCODE.md"), "指令内容");
        } catch (Exception e) {
            fail("写入指令文件失败");
        }
        MemoryStore store = new MemoryStore(tempDir, userHome, MemoryConfig.defaults());

        RequestAssembler assembler = new RequestAssembler(Path.of("."), "test",
                SystemPromptBuilder.defaults().build(), List.of(), loader, store);
        Conversation conversation = new Conversation();

        ProviderRequest first = assembler.assemble(conversation, 1, false, "任务");
        assertEquals(2, first.history().size(), "记忆为空，只注入环境+指令");
        assertTrue(first.history().get(0).userMessage().text().contains("环境信息"));
        assertTrue(first.history().get(1).userMessage().text().contains("指令内容"));
    }

    @Test
    void ordinaryUserTextDoesNotPretendToBeEnvironmentSeed() {
        RequestAssembler assembler = new RequestAssembler(tempDir, "test",
                SystemPromptBuilder.defaults().build(), List.of());
        Conversation conversation = new Conversation();
        conversation.commit(ConversationTurn.userTextOnly("普通用户消息"));
        assertFalse(assembler.isSeeded(conversation));
        assembler.assemble(conversation, 1, false, "新任务");
        assertTrue(assembler.isSeeded(conversation));
        assertEquals("普通用户消息", conversation.snapshot().get(1).userMessage().text());
    }
}
