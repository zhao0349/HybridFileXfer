package top.weixiansen574.hybridfilexfer.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** Small drawing primitives shared by the desktop's card layout. */
public final class DesktopTheme {
    static final Color BACKGROUND = new Color(0xF3F6FB);
    static final Color SURFACE = Color.WHITE;
    static final Color TEXT = new Color(0x172B4D);
    static final Color MUTED = new Color(0x69788F);
    static final Color BLUE = new Color(0x2764E7);
    static final Color BLUE_SOFT = new Color(0xEAF1FF);
    static final Color BORDER = new Color(0xE5EBF4);
    static final Color GREEN = new Color(0x15825D);
    static final Color GREEN_SOFT = new Color(0xE8F6EF);
    static final Color RED = new Color(0xC2414B);
    static final Color RED_SOFT = new Color(0xFFF0F1);

    private DesktopTheme() { }

    /** The window and native installer share this resolution-independent artwork. */
    public static BufferedImage applicationIcon(int size) {
        if (size < 1 || size > 2048) throw new IllegalArgumentException("Icon size must be 1..2048");
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.scale(size / 48.0, size / 48.0);
        new BrandIcon().paintIcon(null, graphics, 0, 0);
        graphics.dispose();
        return image;
    }

    static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    static JLabel label(String value, float size, boolean bold, Color color) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    static final class Card extends JPanel {
        Card(LayoutManager layout) {
            super(layout);
            setOpaque(false);
            setBorder(new EmptyBorder(16, 20, 16, 20));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(SURFACE);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            g.setColor(BORDER);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Keeps the form width responsive and scrolls secondary content on short displays. */
    static final class CardColumn extends JPanel implements Scrollable {
        CardColumn() { super(new GridBagLayout()); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 48);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    static final class Pill extends JLabel {
        private Color fill = BLUE_SOFT;
        Pill() {
            setBorder(new EmptyBorder(7, 13, 7, 13));
            setFont(getFont().deriveFont(Font.BOLD, 12f));
            setForeground(BLUE);
        }

        void state(String text, Color foreground, Color background) {
            setText(text);
            setForeground(foreground);
            fill = background;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static final class BrandIcon implements Icon {
        @Override public int getIconWidth() { return 48; }
        @Override public int getIconHeight() { return 48; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(0x4385F5), 48, 48, new Color(0x2457D8)));
            g.fillRoundRect(0, 0, 48, 48, 15, 15);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D first = new Path2D.Float();
            first.moveTo(12, 17); first.lineTo(35, 17); first.moveTo(29, 11); first.lineTo(35, 17); first.lineTo(29, 23);
            g.draw(first);
            Path2D second = new Path2D.Float();
            second.moveTo(36, 31); second.lineTo(13, 31); second.moveTo(19, 25); second.lineTo(13, 31); second.lineTo(19, 37);
            g.draw(second);
            g.dispose();
        }
    }
}
