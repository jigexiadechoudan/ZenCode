package dev.zencode.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZenBannerTest {
    @Test
    void rendersThreeZenMarkLinesAndMetadataAtNormalWidth() {
        List<ZenBanner.BannerLine> lines = ZenBanner.layout(
                "ZenCode v0.1.0", "deepseek-v4-flash", "C:\\workspace\\demo", 80);

        assertEquals(5, lines.size());
        assertTrue(lines.get(0).mark().contains("█████"));
        assertEquals("ZENCODE", ZenBanner.brandName());
        assertEquals("ZenCode v0.1.0", lines.get(0).metadata().trim());
        assertEquals("deepseek-v4-flash", lines.get(1).metadata().trim());
        assertTrue(lines.get(2).metadata().contains("C:\\workspace\\demo"));
        assertTrue(lines.stream().allMatch(line -> TerminalTextLayout.displayWidth(line.text()) <= 80));
    }

    @Test
    void keepsMarkAndHidesVersionOnCompactWidth() {
        List<ZenBanner.BannerLine> lines = ZenBanner.layout(
                "ZenCode v0.1.0", "deepseek-v4-flash", "C:\\workspace\\demo", 40);

        assertEquals("", lines.get(0).metadata().trim());
        assertTrue(lines.get(1).metadata().contains("deepseek"));
        assertTrue(lines.stream().allMatch(line -> TerminalTextLayout.displayWidth(line.text()) <= 40));
        assertEquals("ZENCODE", lines.get(0).mark());
        assertTrue(lines.get(1).mark().isBlank());
        assertTrue(lines.get(2).mark().isBlank());
    }

    @Test
    void clipsLongMetadataWithoutUsingCatArtwork() {
        List<ZenBanner.BannerLine> lines = ZenBanner.layout(
                "ZenCode v0.1.0", "a-very-long-model-name", "C:\\a\\very\\long\\working\\directory", 32);
        String rendered = lines.stream().map(ZenBanner.BannerLine::text).reduce("", (a, b) -> a + "\n" + b);

        assertTrue(lines.stream().allMatch(line -> TerminalTextLayout.displayWidth(line.text()) <= 32));
        assertFalse(rendered.contains("/\\_/\\"));
        assertFalse(rendered.contains("o.o"));
        assertFalse(rendered.contains("^"));
    }
}
