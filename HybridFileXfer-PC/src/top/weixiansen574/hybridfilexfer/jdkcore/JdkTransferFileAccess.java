package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import top.weixiansen574.hybridfilexfer.core.LocalTransferFileAccess;

public class JdkTransferFileAccess extends LocalTransferFileAccess {
    @Override protected File physicalPath(File file) throws IOException {
        java.nio.file.Path path=file.toPath().toAbsolutePath().normalize();
        java.util.ArrayDeque<String> tail=new java.util.ArrayDeque<>();
        while (!Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (path.getParent()==null) throw new IOException("Cannot resolve filesystem root");
            tail.addFirst(path.getFileName().toString());
            path=path.getParent();
        }
        path=path.toRealPath();
        for (String name:tail) path=path.resolve(name);
        return path.toFile();
    }
    @Override protected void atomicMove(File source, File target, boolean replace) throws IOException {
        if (replace) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            // ATOMIC_MOVE is allowed to overwrite an existing target even without REPLACE_EXISTING.
            if (File.separatorChar == '\\') {
                // Windows MoveFileEx without REPLACE_EXISTING also supports FAT/exFAT.
                Files.move(source.toPath(), target.toPath());
            } else {
                Files.createLink(target.toPath(), source.toPath());
                Files.delete(source.toPath());
            }
        }
    }
}
