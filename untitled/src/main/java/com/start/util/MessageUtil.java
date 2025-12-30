package com.start.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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