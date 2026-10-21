package dev.zencode.web;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The browser asks the local desktop to choose a directory; it never submits an arbitrary path. */
final class ProjectDirectoryPicker implements AutoCloseable {
    private final AtomicBoolean choosing = new AtomicBoolean();
    private volatile JDialog dialog;
    private volatile boolean closed;

    Path choose() throws Exception {
        if (closed || GraphicsEnvironment.isHeadless()) throw new ModelConfigStore.SettingsException(503, "系统文件夹选择器不可用");
        if (!choosing.compareAndSet(false, true)) throw new ModelConfigStore.SettingsException(409, "文件夹选择器已经打开");
        CompletableFuture<Path> selected = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            AtomicReference<Path> result = new AtomicReference<>();
            try {
                if (closed) { selected.complete(null); return; }
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setApproveButtonText("选择文件夹");
                JDialog window = new JDialog((java.awt.Frame) null, "添加项目", true);
                dialog = window;
                chooser.addActionListener(event -> {
                    if (JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand())) {
                        result.set(chooser.getSelectedFile().toPath());
                    }
                    window.dispose();
                });
                window.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                window.add(chooser);
                window.pack();
                window.setLocationRelativeTo(null);
                window.setAlwaysOnTop(true);
                window.setVisible(true);
                selected.complete(result.get());
            } catch (Exception e) { selected.completeExceptionally(new ModelConfigStore.SettingsException(503, "系统文件夹选择器无法打开")); }
            finally { if (dialog != null) dialog.dispose(); dialog = null; }
        });
        try { return selected.get(); }
        catch (java.util.concurrent.ExecutionException e) {
            throw new ModelConfigStore.SettingsException(503, "系统文件夹选择器无法打开");
        }
        finally { choosing.set(false); }
    }

    @Override public void close() {
        closed = true;
        SwingUtilities.invokeLater(() -> { if (dialog != null) dialog.dispose(); });
    }
}
