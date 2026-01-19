// config/DatabaseConfig.java
// config/DatabaseConfig.java
package com.start.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConfig {

    private static HikariDataSource dataSource;
    private static boolean initialized = false;

    /**
     * 初始化数据库连接池（带重试机制）
     */
    public synchronized static void initConnectionPool() {
        if (initialized) return;

        System.out.println("正在初始化数据库连接池...");

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                System.out.println("连接尝试 " + attempt + "/3");

                // 先测试基本连接
                if (!testBasicConnection()) {
                    System.err.println("基本连接测试失败，等待重试...");
                    Thread.sleep(2000);
                    continue;
                }

                // 加载配置
                Properties props = loadProperties();

                // 配置HikariCP
                HikariConfig config = new HikariConfig();

                String dbUrl = props.getProperty("database.url",
                        "jdbc:mysql://localhost:3307/candybear_db" +
                                "?useUnicode=true" +
                                "&characterEncoding=utf8mb4" +
                                "&useSSL=false" +
                                "&allowPublicKeyRetrieval=true" +
                                "&serverTimezone=Asia/Shanghai");

                config.setJdbcUrl(dbUrl);
                config.setUsername(props.getProperty("database.user", "candybear"));
                config.setPassword(props.getProperty("database.password", "YourStrongPassword123!"));

                // 连接池配置
                config.setMaximumPoolSize(10);
                config.setMinimumIdle(2);
                config.setConnectionTimeout(30000);
                config.setIdleTimeout(600000);
                config.setMaxLifetime(1800000);
                config.setLeakDetectionThreshold(60000);

                // MySQL优化
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

                // 连接测试
                config.setConnectionTestQuery("SELECT 1");
                config.setValidationTimeout(5000);

                dataSource = new HikariDataSource(config);

                // 测试连接池
                try (Connection conn = dataSource.getConnection()) {
                    System.out.println("✅ 数据库连接池初始化成功");
                    System.out.println("连接URL: " + dbUrl);
                    System.out.println("连接池状态: " + getPoolStatus());
                }

                initialized = true;
                return;

            } catch (Exception e) {
                System.err.println("连接尝试 " + attempt + " 失败: " + e.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    System.err.println("❌ 数据库连接池初始化失败，将使用降级模式");
                    System.err.println("提示：请检查：");
                    System.err.println("1. SSH隧道是否启动 (ssh -L 3307:localhost:3306 ...)");
                    System.err.println("2. MySQL服务是否运行");
                    System.err.println("3. 数据库用户密码是否正确");
                }
            }
        }

        // 如果所有尝试都失败，设置一个标志
        System.err.println("警告：数据库连接失败，相关功能将不可用");
    }

    /**
     * 测试基本连接
     */
    private static boolean testBasicConnection() {
        try {
            Properties props = loadProperties();
            String url = props.getProperty("database.url",
                    "jdbc:mysql://localhost:3307/candybear_db");
            String user = props.getProperty("database.user", "candybear");
            String password = props.getProperty("database.password", "YourStrongPassword123!");

            System.out.println("测试连接: " + url);

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                System.out.println("✅ 基本连接测试成功");
                return true;
            }
        } catch (SQLException e) {
            System.err.println("基本连接测试失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取数据库连接
     */
    public static Connection getConnection() throws SQLException {
        if (!initialized) {
            initConnectionPool();
        }

        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("数据库连接池不可用");
        }

        return dataSource.getConnection();
    }

    /**
     * 关闭连接池
     */
    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("数据库连接池已关闭");
        }
    }

    /**
     * 获取连接池状态
     */
    public static String getPoolStatus() {
        if (dataSource == null) return "连接池未初始化";

        try {
            var pool = dataSource.getHikariPoolMXBean();
            return String.format("活跃=%d, 空闲=%d, 等待=%d, 总计=%d",
                    pool.getActiveConnections(),
                    pool.getIdleConnections(),
                    pool.getThreadsAwaitingConnection(),
                    pool.getTotalConnections());
        } catch (Exception e) {
            return "获取状态失败: " + e.getMessage();
        }
    }

    /**
     * 加载配置文件
     */
    private static Properties loadProperties() {
        Properties props = new Properties();

        try (InputStream is = DatabaseConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
                System.out.println("加载配置文件成功");
            }
        } catch (Exception e) {
            System.err.println("加载配置文件失败，使用默认值");
        }

        return props;
    }
    public static HikariDataSource getDataSource() {
        if (!initialized) {
            initConnectionPool();
        }
        if (dataSource == null || dataSource.isClosed()) {
            throw new IllegalStateException("数据库连接池初始化失败或已关闭");
        }
        return dataSource;
    }
}