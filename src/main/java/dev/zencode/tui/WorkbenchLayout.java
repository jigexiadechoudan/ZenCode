package dev.zencode.tui;

/** 根据真实终端尺寸分配工作台区域；不虚构比屏幕更大的画布。 */
record WorkbenchLayout(int width, int height, int left, int contentWidth,
                       int headerRows, int transcriptTop, int transcriptBottom,
                       int conversationWidth, int railLeft, int railWidth,
                       int composerLeft, int composerWidth, int inputSeparator,
                       int visibleInputLines, int footerTop) {

    static WorkbenchLayout of(int columns, int rows, int requestedInputLines, int overlayHeight) {
        int width = Math.max(1, columns);
        int height = Math.max(1, rows);
        int left = width >= 40 ? 2 : width >= 8 ? 1 : 0;
        int contentWidth = Math.max(1, width - 2 * left);
        int headerRows = height >= 16 ? 3 : height >= 9 ? 1 : 0;
        int transcriptTop = headerRows;
        int footerTop = Math.max(0, height - 2);
        int availableForInput = Math.max(1, footerTop - transcriptTop - 1);
        int visibleInputLines = Math.min(Math.min(4, Math.max(1, requestedInputLines)), availableForInput);
        int inputSeparator = Math.max(transcriptTop, footerTop - visibleInputLines - 1);
        int transcriptBottom = Math.max(transcriptTop,
                inputSeparator - Math.min(Math.max(0, overlayHeight), Math.max(0, inputSeparator - transcriptTop)));

        boolean showRail = contentWidth >= 108 && transcriptBottom - transcriptTop >= 10;
        int railWidth = showRail ? Math.min(34, Math.max(26, contentWidth / 4)) : 0;
        int conversationWidth = contentWidth - (showRail ? railWidth + 2 : 0);
        int railLeft = showRail ? left + conversationWidth + 2 : -1;
        int composerWidth = Math.min(contentWidth, 88);
        int composerLeft = left + Math.max(0, (contentWidth - composerWidth) / 2);
        return new WorkbenchLayout(width, height, left, contentWidth, headerRows,
                transcriptTop, transcriptBottom, conversationWidth, railLeft, railWidth,
                composerLeft, composerWidth, inputSeparator, visibleInputLines, footerTop);
    }

    boolean hasRail() {
        return railWidth > 0;
    }
}
