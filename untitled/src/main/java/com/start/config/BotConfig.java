package com.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class BotConfig {
    private static final Logger logger = LoggerFactory.getLogger(BotConfig.class);
    private static long botQq;
    private static String botName;

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
            logger.info("🤖 机器人 QQ: {}, 名字: {}", botQq, botName);
        } catch (Exception e) {
            logger.error("❌ 加载配置失败", e);
            throw new RuntimeException("配置加载失败，请检查 application.properties", e);
        }
    }

    public static long getBotQq() {
        return botQq;
    }

    public static String getBotName() {
        return botName;
    }
}