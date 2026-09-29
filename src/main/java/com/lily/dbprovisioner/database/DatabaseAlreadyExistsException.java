package com.lily.dbprovisioner.database;

public class DatabaseAlreadyExistsException extends RuntimeException {

    public DatabaseAlreadyExistsException(String projectId) {
        super("database already exists for project: " + projectId);
    }
}
