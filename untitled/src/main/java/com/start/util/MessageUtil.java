package com.start.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MessageUtil {
    private static final Logger logger = LoggerFactory.getLogger(MessageUtil.class);

    /**
     * 提取纯文本内容（忽略 at/image 等）
     */
    public static String extractPlainText(JsonNode messageNode) {
        if (messageNode == null || messageNode.isNull()) return "";
        if (messageNode.isTextual()) return messageNode.asText();

        StringBuilder sb = new StringBuilder();
        if (messageNode.isArray()) {
            for (JsonNode seg : messageNode) {
                if ("text".equals(seg.path("type").asText())) {
                    sb.append(seg.path("data").path("text").asText());
                }
            }
        }
        return sb.toString();
    }

    public static String extractPlainText(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        boolean insideCq = false;

        for (int i = 0; i < rawMessage.length(); i++) {
            char c = rawMessage.charAt(i);

            if (!insideCq) {
                // 检查是否遇到 "[CQ:"
                if (c == '[' && i + 4 <= rawMessage.length()
                        && rawMessage.startsWith("CQ:", i + 1)) {
                    insideCq = true; // 进入 CQ 标签，跳过
                } else {
                    result.append(c); // 普通字符，保留
                }
            } else {
                // 已经在 CQ 标签内部，寻找 ']'
                if (c == ']') {
                    insideCq = false; // 结束标签，继续正常处理
                }
                // 否则继续跳过（不 append）
            }
        }

        return result.toString();
    }

    /**
     * 提取消息中所有被 @ 的 QQ 号
     */
    public static List<Long> extractAts(JsonNode messageNode) {
        List<Long> ats = new ArrayList<>();
        if (messageNode == null || messageNode.isNull()) return ats;

        if (messageNode.isArray()) {
            for (JsonNode seg : messageNode) {
                if ("at".equals(seg.path("type").asText())) {
                    try {
                        long qq = seg.path("data").path("qq").asLong();
                        ats.add(qq);
                    } catch (Exception e) {
                        logger.warn("解析 @ 段失败: {}", seg);
                    }
                }
            }
        }
        return ats;
    }

    /**
     * 判断消息是否 @ 了指定的 QQ
     */
    public static boolean isAt(JsonNode messageNode, long targetQq) {
        List<Long> ats = extractAts(messageNode);
        return ats.contains(targetQq);
    }
}