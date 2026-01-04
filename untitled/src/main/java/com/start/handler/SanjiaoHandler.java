package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;

import com.start.Main;
import com.start.service.WebScreenshotService;
import com.start.util.MessageUtil;

import java.util.concurrent.CompletableFuture;
import java.util.Base64;
import java.io.IOException;

/**
 * 处理三角（Sanjiao）相关截图命令的 Handler
 * 触发关键词：包含 "截图" 且包含 "三角"、"sanjiao"、"kkrb" 等
 */
public class SanjiaoHandler implements MessageHandler {

    private final WebScreenshotService screenshotService = new WebScreenshotService();

    @Override
    public boolean match(JsonNode message) {
        String plainText = MessageUtil.extractPlainText(message.path("message"));

        if (plainText == null) {
            plainText = "";
        }
        plainText = plainText.trim();
        boolean isExactKeyword = "特勤处".equals(plainText) || "制作".equals(plainText);

        return isExactKeyword;
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        long groupId = message.get("group_id").asLong();

        // 异步执行截图
        CompletableFuture<String> future = screenshotService.takeScreenshot("kkrb-overview");

        future.thenCompose(imagePath -> {
            try {
                // 读取图片并自动清理临时文件
                byte[] imageBytes = screenshotService.readAndCleanupImage(imagePath);
                String base64 = Base64.getEncoder().encodeToString(imageBytes);
                String cqImage = "[CQ:image,file=base64://" + base64 + "]";
                bot.sendGroupReply(groupId, cqImage);
                return CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                throw new RuntimeException("读取截图文件失败", e);
            }
        }).exceptionally(ex -> {
            String errorMsg = "❌ 特勤处截图失败";
            Throwable cause = ex.getCause();
            if (cause != null && cause.getMessage() != null) {
                // 截取关键错误信息，避免泄露路径等敏感内容
                String msg = cause.getMessage();
                if (msg.length() > 50) {
                    msg = msg.substring(0, 50) + "...";
                }
                errorMsg += "：" + msg;
            }
            bot.sendGroupReply(groupId, errorMsg);
            return null;
        });
    }
}