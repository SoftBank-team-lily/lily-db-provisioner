package com.lily.dbprovisioner.database;

public class DatabaseNotReadyException extends RuntimeException {

    public DatabaseNotReadyException(String id, DatabaseStatus status) {
        super("database not available: id=" + id + " status=" + status);
    }
}
