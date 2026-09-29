package com.lily.dbprovisioner.database;

public class DatabaseNotFoundException extends RuntimeException {

    public DatabaseNotFoundException(String id) {
        super("database not found: id=" + id);
    }
}
