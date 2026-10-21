package dev.zencode.worktree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlugValidatorTest {

    @Test
    void 合法名通过并平铺斜杠() {
        assertEquals("feature-a", SlugValidator.validate("feature-a"));
        assertEquals("a-b-c", SlugValidator.validate("a/b/c"));
        assertEquals("my_feature-1", SlugValidator.validate("my_feature-1"));
    }

    @Test
    void 拒绝空名() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(""));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("   "));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(null));
    }

    @Test
    void 拒绝点与点点段() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("."));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(".."));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a/../b"));
    }

    @Test
    void 拒绝盘符与绝对路径() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("C:\\x"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("C:/x"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("/etc"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("\\x"));
    }

    @Test
    void 拒绝非法字符() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a*b"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a?b"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a:b"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a b"));
    }

    @Test
    void 拒绝超长名() {
        String longName = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate(longName));
    }

    @Test
    void 拒绝空段() {
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a//b"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("/"));
    }

    @Test
    void flatten仅平铺不校验() {
        assertEquals("a-b", SlugValidator.flatten("a/b"));
        assertEquals("", SlugValidator.flatten(null));
    }
}
