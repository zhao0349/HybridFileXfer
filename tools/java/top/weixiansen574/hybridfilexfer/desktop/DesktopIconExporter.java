package top.weixiansen574.hybridfilexfer.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

/** Export the author's original logo, shared with the actual application window. */
public final class DesktopIconExporter {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        for (int size : new int[]{256, 512}) {
            ImageIO.write(DesktopTheme.applicationIcon(size), "png",
                    output.resolve("HybridFileXfer-" + size + ".png").toFile());
        }
    }
}
