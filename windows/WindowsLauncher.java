import java.awt.GraphicsEnvironment;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.zip.CRC32;
import javax.swing.JOptionPane;

public final class WindowsLauncher {
    private static final String[] ASSETS = {"start.ps1", "service.jar", "kafka.tgz"};

    public static void main(String[] args) {
        try {
            if (!System.getProperty("os.name").startsWith("Windows")) {
                throw new IOException("Use the service JAR or Docker Compose on this operating system.");
            }
            Path ownJar = Paths.get(WindowsLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String override = System.getenv("OMNISTREAM_HOME");
            Path home = override == null || override.isEmpty()
                ? ownJar.getParent().resolve("Omnistream-data")
                : Paths.get(override).toAbsolutePath().normalize();
            Path state = home.resolve("state");
            Files.createDirectories(state);
            if (args.length == 1 && args[0].equals("--stop")) {
                Files.write(state.resolve("stop.request"), new byte[0]);
                return;
            }
            boolean attached = args.length == 1 && args[0].equals("--console");
            if (args.length > 0 && !attached) throw new IOException("Supported options: --console or --stop.");
            Path runtime = home.resolve("runtime").resolve(fingerprint(ownJar));
            Files.createDirectories(runtime);
            try (FileChannel channel = FileChannel.open(runtime.resolve("unpack.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                for (String asset : ASSETS) unpack(asset, runtime);
            }
            ProcessBuilder process;
            if (attached || System.console() != null) {
                process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "start.ps1");
            } else {
                // Explorer starts javaw without a console. A visible console is needed for the key prompt.
                process = new ProcessBuilder("cmd.exe", "/d", "/c", "start", "\"Omnistream\"", "powershell.exe",
                    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "start.ps1");
            }
            process.environment().put("OMNISTREAM_HOME", home.toString());
            if (attached || System.console() != null) process.inheritIO();
            int code = process.directory(runtime.toFile()).start().waitFor();
            System.exit(code);
        } catch (Exception failure) {
            String message = "Omnistream could not start. " + failure.getMessage();
            System.err.println(message);
            if (!GraphicsEnvironment.isHeadless() && System.console() == null)
                JOptionPane.showMessageDialog(null, message, "Omnistream", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private static String fingerprint(Path file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[131072];
        try (InputStream stream = Files.newInputStream(file)) {
            int n;
            while ((n = stream.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) hex.append(String.format("%02x", b));
        return hex.substring(0, 16);
    }

    private static void unpack(String name, Path runtime) throws IOException {
        Path target = runtime.resolve(name);
        URL resource = WindowsLauncher.class.getResource("/payload/" + name);
        if (resource == null) throw new IOException("Missing packaged resource: " + name);
        JarURLConnection archive = (JarURLConnection) resource.openConnection();
        long expected = archive.getJarEntry().getCrc();
        if (Files.isRegularFile(target) && crc(target) == expected) return;
        // Some Windows profile directories span volumes. Copy under the extraction lock.
        try (InputStream input = archive.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (crc(target) != expected) throw new IOException("Incomplete extraction: " + target);
    }

    private static long crc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[131072];
        try (InputStream input = Files.newInputStream(file)) {
            int n;
            while ((n = input.read(buffer)) != -1) crc.update(buffer, 0, n);
        }
        return crc.getValue();
    }
}
