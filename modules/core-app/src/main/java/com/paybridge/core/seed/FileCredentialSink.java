package com.paybridge.core.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Writes {@code username=password} lines to a local file (tests and local runs outside Kubernetes), mode 600 where supported. */
public final class FileCredentialSink implements CredentialSink {
    private final Path file;

    public FileCredentialSink(Path file) {
        this.file = file;
    }

    @Override
    public void store(Map<String, String> passwordsByUsername) {
        try {
            var merged = new TreeMap<String, String>();
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        merged.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            } else {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.createFile(file);
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system (Windows); the directory ACL applies
                }
            }
            merged.putAll(new LinkedHashMap<>(passwordsByUsername));
            var out = new StringBuilder();
            merged.forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the credential file", e);
        }
    }
}
