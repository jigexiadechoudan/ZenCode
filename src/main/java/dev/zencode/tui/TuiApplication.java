package dev.zencode.tui;

import com.googlecode.lanterna.screen.Screen;
import dev.zencode.app.ConversationController;

public final class TuiApplication implements AutoCloseable {
    private final Screen screen;
    private final ConversationController controller;
    private final LanternaConversationView view;

    public TuiApplication(Screen screen, ConversationController controller, LanternaConversationView view) {
        this.screen = screen; this.controller = controller; this.view = view;
    }

    public void run() throws Exception { screen.startScreen(); view.show(); }
    @Override public void close() { controller.close(); view.close(); try { screen.stopScreen(); } catch (Exception ignored) {} }
}
