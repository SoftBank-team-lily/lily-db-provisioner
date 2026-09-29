package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties.EngineSettings;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.lily.dbprovisioner.engine.Credentials.checkName;
import static com.lily.dbprovisioner.engine.Credentials.checkPassword;

/** MySQL 8.0 이상 */
class MysqlProvisioner extends JdbcEngineProvisioner {

    MysqlProvisioner(EngineSettings settings) {
        super(settings, "admin-mysql");
    }

    @Override
    public Engine engine() {
        return Engine.MYSQL;
    }

    @Override
    public void create(String name, String password, int connectionLimit) {
        checkName(name);
        checkPassword(password);
        exec("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        exec("CREATE USER '" + name + "'@'%' IDENTIFIED BY '" + password
                + "' WITH MAX_USER_CONNECTIONS " + connectionLimit);
        exec("GRANT ALL PRIVILEGES ON `" + name + "`.* TO '" + name + "'@'%'");
    }

    @Override
    public void drop(String name) {
        checkName(name);
        exec("DROP USER IF EXISTS '" + name + "'@'%'");
        exec("DROP DATABASE IF EXISTS `" + name + "`");
    }

    @Override
    public Map<String, String> env(String name, String password) {
        String hostPort = publicHost() + ":" + publicPort();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_URL", "jdbc:mysql://" + hostPort + "/" + name);
        env.put("DB_USERNAME", name);
        env.put("DB_PASSWORD", password);
        env.put("DATABASE_URL", "mysql://" + name + ":" + password + "@" + hostPort + "/" + name);
        return env;
    }
}
