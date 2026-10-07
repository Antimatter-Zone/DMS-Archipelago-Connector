package com.antimatterzone.apconnector;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/** Cross-process, same-user lease. Filenames contain only a SHA-256, never aliases or credentials. */
final class SessionLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    static Path defaultRoot() {
        String local=System.getenv("LOCALAPPDATA");
        Path root=local==null || local.isBlank()?Path.of(System.getProperty("user.home"),".local","share"):Path.of(local);
        return root.resolve("DMS Archipelago Connector").resolve("leases");
    }
    static SessionLease acquire(Path directory,String key) throws Exception {
        if(Files.isSymbolicLink(directory)) throw new IOException("Linked lease directory is not allowed.");
        Files.createDirectories(directory);LocalState.ownerOnly(directory);
        Path path=directory.resolve(Json.hash(key)+".lock");
        if(Files.isSymbolicLink(path)) throw new IOException("Linked lease file is not allowed.");
        return new SessionLease(path);
    }
    private SessionLease(Path path) throws IOException {
        channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {lock=channel.tryLock();}catch(Exception error) {channel.close();throw new IOException("This AP connection is already owned by another connector.");}
        if(lock==null) {channel.close();throw new IOException("This AP connection is already owned by another connector.");}
        // Lock files intentionally stay empty; deleting them creates inode/handle races across processes.
    }
    public synchronized void close() {
        if(closed)return;closed=true;
        try {lock.release();}catch(IOException ignored) { }
        try {channel.close();}catch(IOException ignored) { }
    }
}
