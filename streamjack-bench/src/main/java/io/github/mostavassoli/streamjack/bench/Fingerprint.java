package io.github.mostavassoli.streamjack.bench;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** SHA-256 over files, so a resumed run can prove nothing that shapes its results has changed. */
final class Fingerprint {

    private Fingerprint() {
    }

    /** Hash of the files' relative names and contents, in a stable order; a directory contributes all its files. */
    static String of(List<Path> roots) {
        MessageDigest digest = sha256();
        for (Path root : roots) {
            boolean tree = Files.isDirectory(root);
            try (Stream<Path> files = tree ? Files.walk(root) : Stream.of(root)) {
                // Names are normalized to forward slashes and sorted as strings, so the same tree has the same hash
                // on every OS (native Path ordering differs between file systems).
                java.util.TreeMap<String, Path> byName = new java.util.TreeMap<>();
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    byName.put((tree ? root.relativize(file) : file.getFileName()).toString().replace('\\', '/'), file);
                }
                for (var named : byName.entrySet()) {
                    String name = named.getKey();
                    Path file = named.getValue();
                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                        // Git may check text out with CRLF on Windows and LF elsewhere; the content is the same.
                        digest.update(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")
                                .getBytes(StandardCharsets.UTF_8));
                        continue;
                    }
                    try (InputStream in = Files.newInputStream(file)) {
                        byte[] buffer = new byte[65536];
                        for (int read; (read = in.read(buffer)) > 0; ) {
                            digest.update(buffer, 0, read);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot fingerprint " + root, e);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The harness's own code (its jar, or its classes directory in a build). */
    static String ofHarness() {
        try {
            return of(List.of(Path.of(Fingerprint.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
