import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Test-only, for test/live/upgrade.mjs: holds a world's session.lock the way a running server does, until its
 * stdin closes, so preflight-upgrade can be shown to refuse while nothing answers on the port but a world is open.
 * Run as: java HoldLock.java <world>/session.lock
 */
public final class HoldLock {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            System.out.println(lock != null ? "held" : "busy");
            System.out.flush();
            while (System.in.read() >= 0) {
                // Wait for stdin to close.
            }
        }
    }
}
