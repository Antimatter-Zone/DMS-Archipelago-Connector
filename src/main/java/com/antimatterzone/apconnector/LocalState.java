package com.antimatterzone.apconnector;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Credential-free replay/outbox. Games remain responsible for atomically saving applied effects. */
final class LocalState implements AutoCloseable {
    private final Path directory, journal;
    private final FileChannel channel;
    private final FileLock lock;
    LocalState(Path instance) throws IOException {
        if (!Files.isDirectory(instance) || Files.isSymbolicLink(instance)) throw new IOException("Choose an existing managed instance directory.");
        directory = instance.resolve(".dms-connector");
        if (Files.isSymbolicLink(directory)) throw new IOException("Linked state directory is not allowed.");
        Files.createDirectories(directory); ownerOnly(directory);
        journal = directory.resolve("connector-state.json");
        Path lockPath = directory.resolve("connector.lock"); rejectLink(journal); rejectLink(lockPath);
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = channel.tryLock(); } catch (Exception error) { channel.close(); throw new IOException("This instance already has a connector."); }
        if (lock == null) { channel.close(); throw new IOException("This instance already has a connector."); }
    }
    JsonNode read() throws IOException {
        if (!Files.exists(journal)) return null;
        if (Files.size(journal) > 16_777_216) throw new IOException("State exceeds supported size.");
        JsonNode node = Json.MAPPER.readTree(Files.readAllBytes(journal));
        if (node == null || !node.isObject()) throw new IOException("Invalid connector state.");
        return node;
    }
    void write(Object value) throws IOException { atomicWrite(journal, Json.MAPPER.writeValueAsBytes(value)); }
    Path writeLaunch(Object value) throws IOException {
        Path file = directory.resolve("dms-ap-config.json"); atomicWrite(file, Json.MAPPER.writeValueAsBytes(value)); return file;
    }
    void clearLaunch() throws IOException { Files.deleteIfExists(directory.resolve("dms-ap-config.json")); }
    private static void rejectLink(Path path) throws IOException { if (Files.isSymbolicLink(path)) throw new IOException("Linked state files are not allowed."); }
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        rejectLink(target); Path temporary = target.resolveSibling(target.getFileName()+".tmp"); rejectLink(temporary);
        try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ownerOnly(temporary); ByteBuffer buffer = ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) file.write(buffer); file.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static void ownerOnly(Path path) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        }
    }
    @Override public void close() throws IOException { try { clearLaunch(); } finally { try { lock.release(); } finally { channel.close(); } } }
}
