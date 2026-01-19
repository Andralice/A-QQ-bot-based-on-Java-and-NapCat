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
        boolean isExactKeyword = "特勤处".equals(plainText) || "脑机".equals(plainText) || "密码".equals(plainText);

        return isExactKeyword;
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        long groupId = message.get("group_id").asLong();
        String plainText = MessageUtil.extractPlainText(message.path("message"));

        CompletableFuture<String> future = null;

        if ("特勤处".equals(plainText)) {
            future = screenshotService.takeScreenshot("kkrb-overview");
        } else if ("脑机".equals(plainText)) {
            future = screenshotService.takeScreenshot("kkrb-overview-2");
        } else if ("密码".equals(plainText)) {
            future = screenshotService.takeScreenshot("kkrb-overview-3");
        }

        // 如果没有匹配命令，直接返回（不处理）
        if (future == null) {
            return;
        }

        // 异步执行截图后续操作
        future.thenCompose(imagePath -> {
            try {
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
                String msg = cause.getMessage();
                if (msg.length() > 100) {
                    msg = msg.substring(0,100) + "...";
                }
                errorMsg += "：" + msg;
            }
            bot.sendGroupReply(groupId, errorMsg);
            return null;
        });
    }
}