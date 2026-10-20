package top.weixiansen574.hybridfilexfer.desktop;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.table.DefaultTableModel;
import top.weixiansen574.hybridfilexfer.core.TransferProgress;
import java.util.concurrent.atomic.AtomicReference;

/** Run manually without headless=true. Renders real Swing widgets without ADB or a phone. */
public final class DesktopVisualSmokeTest {
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Desktop visual smoke skipped: no display (run manually on a desktop).");
            return;
        }
        Path destination = Paths.get(args.length == 0 ? ".build/desktop-agent/ui-smoke.png" : args[0]).toAbsolutePath();
        Files.createDirectories(destination.getParent());
        System.setProperty("hfx.dataDir", destination.getParent().resolve("smoke-data").toString());
        System.setProperty("hfx.smokeTest", "true");
        System.setProperty("java.util.prefs.userRoot", destination.getParent().resolve("smoke-preferences").toString());
        AtomicReference<JFrame> testedFrame = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                DesktopApp.configureLookAndFeel();
                if (!(UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatLaf))
                    throw new AssertionError("The modern FlatLaf theme was not installed");
                DesktopApp desktop = new DesktopApp(LaunchOptions.parse(new String[0]));
                JFrame frame = desktop.windowForTesting();
                testedFrame.set(frame);
                frame.addNotify();
                frame.validate();
                Dimension normalSize = frame.getSize();
                render(frame, destination);
                JComboBox<?> mode = (JComboBox<?>) field(desktop, "mode");
                mode.setSelectedIndex(1);
                ((JTextField) field(desktop, "address")).setText("192.168.1.24");
                frame.validate();
                render(frame, destination.resolveSibling("ui-network.png"));
                frame.setSize(960, 680); frame.validate();
                assertVisible((JComponent) field(desktop, "connect"), "Connect in small window");
                assertVisible((JComponent) field(desktop, "stop"), "Disconnect in small window");
                assertVisible((JComponent) field(desktop, "cancelTransfer"), "Cancel in small window");
                assertVisible((JComponent) field(desktop, "reconnect"), "Reconnect preference in small window");
                assertVisible((JComponent) field(desktop, "launchPhone"), "Phone launch preference in small window");
                render(frame, destination.resolveSibling("ui-minimum.png"));
                JScrollPane content = (JScrollPane) field(desktop, "contentScroll");
                content.getVerticalScrollBar().setValue(content.getVerticalScrollBar().getMaximum());
                render(frame, destination.resolveSibling("ui-minimum-bottom.png"));
                content.getVerticalScrollBar().setValue(0);
                frame.setSize(normalSize); mode.setSelectedIndex(0); frame.validate();
                Field tableField = DesktopApp.class.getDeclaredField("channels");
                tableField.setAccessible(true);
                DefaultTableModel channels = (DefaultTableModel) tableField.get(desktop);
                channels.addRow(new Object[]{"USB_ADB", "39.8 MiB/s", "0 B/s"});
                channels.addRow(new Object[]{"Wi-Fi", "108.4 MiB/s", "0 B/s"});
                Method start = DesktopApp.class.getDeclaredMethod("started", boolean.class);
                start.setAccessible(true); start.invoke(desktop, true);
                Method busy = DesktopApp.class.getDeclaredMethod("setBusy", boolean.class);
                busy.setAccessible(true); busy.invoke(desktop, true);
                Method state = DesktopApp.class.getDeclaredMethod("updateState", ConnectionController.State.class, String.class);
                state.setAccessible(true); state.invoke(desktop, ConnectionController.State.TRANSFERRING,
                        DesktopText.text("正在发送到手机…（合成测试数据）", "Sending to phone… (synthetic test data)"));
                TransferProgress progress = new TransferProgress();
                progress.totalBytes = 1024L * 1024 * 1024 * 5;
                progress.transferredBytes = progress.totalBytes * 3 / 5;
                progress.completedFiles = 36; progress.totalFiles = 60;
                progress.currentPath = "Synthetic example / Holiday photos / IMG_0017.jpg";
                Method show = DesktopApp.class.getDeclaredMethod("showProgress", TransferProgress.class);
                show.setAccessible(true); show.invoke(desktop, progress);
                frame.validate();
                JTable channelTable = findTable(frame.getContentPane(), channels);
                if (channelTable == null || (normalSize.height >= 800
                        && !channelTable.getVisibleRect().contains(channelTable.getCellRect(1, 0, true))))
                    throw new AssertionError("Default window clips the second transfer channel");
                render(frame, destination.resolveSibling("ui-smoke-transfer.png"));
                frame.setSize(960, 680); frame.validate();
                assertVisible((JComponent) field(desktop, "stop"), "Disconnect while transferring in small window");
                assertVisible((JComponent) field(desktop, "cancelTransfer"), "Cancel while transferring in small window");
                assertVisible((JComponent) field(desktop, "reconnect"), "Reconnect preference while transferring");
                assertVisible((JComponent) field(desktop, "launchPhone"), "Phone launch preference while transferring");
                if (!((JButton) field(desktop, "stop")).isEnabled()
                        || !((JButton) field(desktop, "cancelTransfer")).isEnabled())
                    throw new AssertionError("Transfer stop actions are unavailable");
                render(frame, destination.resolveSibling("ui-minimum-transfer.png"));
                Field active = DesktopApp.class.getDeclaredField("transferActive");
                active.setAccessible(true); active.set(desktop, false);
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        awaitClosed(testedFrame.get());
        // Reopen a real Swing window instance, then exercise the same user-close handler.
        SwingUtilities.invokeAndWait(() -> {
            try {
                DesktopApp reopened = new DesktopApp(LaunchOptions.parse(new String[0]));
                JFrame frame = reopened.windowForTesting();
                testedFrame.set(frame); frame.addNotify(); frame.validate();
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        awaitClosed(testedFrame.get());
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive() && thread.getName().equals("hfx-desktop-utility"))
                throw new AssertionError("Desktop utility thread survived window close");
        System.out.println("Rendered desktop without device access: " + destination);
        System.out.println("Close/reopen checks passed; desktop utility threads were reclaimed.");
        System.exit(0);
    }
    private static void awaitClosed(JFrame frame) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> open = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> open.set(frame.isDisplayable()));
            if (!open.get()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Window close did not finish");
    }
    private static void render(JFrame frame, Path path) throws Exception {
        Container content = frame.getContentPane();
        BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        content.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
    }
    private static Object field(DesktopApp desktop, String name) throws Exception {
        Field value = DesktopApp.class.getDeclaredField(name);
        value.setAccessible(true);
        return value.get(desktop);
    }
    private static void assertVisible(JComponent component, String description) {
        Rectangle visible = component.getVisibleRect();
        if (component.getWidth() < 1 || component.getHeight() < 1
                || visible.width < component.getWidth() || visible.height < component.getHeight())
            throw new AssertionError(description + " is clipped: " + visible + " of " + component.getSize());
    }
    private static JTable findTable(Container parent, DefaultTableModel model) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTable && ((JTable) child).getModel() == model) return (JTable) child;
            if (child instanceof Container) {
                JTable table = findTable((Container) child, model);
                if (table != null) return table;
            }
        }
        return null;
    }
}
