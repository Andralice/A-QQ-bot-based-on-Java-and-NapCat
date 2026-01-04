package com.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class BotConfig {
    private static final Logger logger = LoggerFactory.getLogger(BotConfig.class);
    private static long botQq;
    private static String botName;
    private static Set<Long> ALLOWED_GROUPS ;
    private static Set<Long> ALLOWED_PRIVATE_USERS ;
    private static boolean privateWhitelistEnabled = false;
    static {
        try (InputStream is = BotConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new RuntimeException("❌ 未找到 application.properties");
            }

            Properties props = new Properties();
            // 👇 关键：用 UTF-8 显式解码！
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));

            // 读取配置
            String qqStr = props.getProperty("bot.qq");
            if (qqStr == null || qqStr.trim().isEmpty()) {
                throw new RuntimeException("❌ 请配置 bot.qq");
            }
            botQq = Long.parseLong(qqStr.trim());

//            botName = props.getProperty("bot.name", "机器人").trim();
            botName = "糖果熊";
            String enabledStr = props.getProperty("private.whitelist.enabled", "false").trim();
            privateWhitelistEnabled = Boolean.parseBoolean(enabledStr);
            ALLOWED_GROUPS = parseLongSet(props.getProperty("allowed.groups", ""));
            ALLOWED_PRIVATE_USERS = parseLongSet(props.getProperty("allowed.private.users", ""));
            logger.info("🤖 机器人 QQ: {}, 名字: {}", botQq, botName);
            logger.info("🤖 机器人 QQ: {}, 名字: {}", botQq, botName);
            logger.info("✅ 白名单群: {}", ALLOWED_GROUPS);
            logger.info("🔒 私聊白名单开关: {}", privateWhitelistEnabled ? "ON" : "OFF");
            if (privateWhitelistEnabled) {
                logger.info("✅ 私聊白名单用户: {}", ALLOWED_PRIVATE_USERS);
            } else {
                logger.info("✅ 所有私聊消息将被允许");
            }
        } catch (Exception e) {
            logger.error("❌ 加载配置失败", e);
            throw new RuntimeException("配置加载失败，请检查 application.properties", e);
        }
    }
    private static Set<Long> parseLongSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet(); // 空配置 → 返回空集合
        }
        return Arrays.stream(value.split(","))     // 按逗号分割
                .map(String::trim)            // 去掉空格（如 " 123 " → "123"）
                .filter(s -> !s.isEmpty())    // 过滤掉空字符串
                .map(Long::parseLong)         // 把 "123" 转成 Long 类型 123L
                .collect(Collectors.toSet()); // 收集成 Set<Long>
    }

    public static long getBotQq() {
        return botQq;
    }

    public static String getBotName() {
        return botName;
    }

    public static Set<Long> getAllowedGroups() { return ALLOWED_GROUPS; }
    public static Set<Long> getAllowedPrivateUsers() { return ALLOWED_PRIVATE_USERS; }
    // 新增 getter
    public static boolean isPrivateWhitelistEnabled() {
        return privateWhitelistEnabled;
    }
    public static String getAt(long userId) {
        return "[CQ:at,qq=" + userId + "]";
    }
}