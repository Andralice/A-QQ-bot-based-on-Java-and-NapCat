package com.start.agent;

import com.start.Main;
import com.start.service.TtsService;

import java.util.*;

/**
 * AI 语音工具。糖果熊在群里"说话"，萌感拉满。
 * 通过 TtsService 调用 text-to-speech.cn 生成语音 MP3 文件，
 * 再通过 [CQ:record,file=file:///path] 发送到群聊。
 */
public class VoiceTool implements Tool {
    private final Main bot;
    private final TtsService ttsService;
    private final Map<String, Long> lastVoiceTime = new HashMap<>();
    private static final long COOLDOWN_MS = 60_000; // 1分钟冷却

    public VoiceTool(Main bot, TtsService ttsService) {
        this.bot = bot;
        this.ttsService = ttsService;
    }

    @Override public String getName() { return "send_voice"; }

    @Override
    public String getDescription() { return "在群里发送AI语音消息。用于重要通知、游戏喊人、特别时刻。别频繁用。"; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "group_id", Map.of("type", "string", "description", "群号"),
                        "text", Map.of("type", "string", "description", "要说的话（会转成语音），10-30字最合适")
                ),
                "required", Arrays.asList("group_id", "text"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String groupId = (String) args.get("group_id");
        String text = (String) args.get("text");
        if (groupId == null || text == null) return "缺少 group_id 或 text";

        long now = System.currentTimeMillis();
        Long last = lastVoiceTime.get(groupId);
        if (last != null && now - last < COOLDOWN_MS) {
            return "语音冷却中，稍后再发";
        }
        lastVoiceTime.put(groupId, now);

        if (text.length() > 100) text = text.substring(0, 100);

        try {
            String filePath = ttsService.synthesizeToFile(text);
            if (filePath == null) return "语音合成失败，TTS 服务未就绪";

            // 读取文件 base64 → CQ 码发送
            java.nio.file.Path audioFile = java.nio.file.Paths.get(filePath);
            byte[] audioBytes = java.nio.file.Files.readAllBytes(audioFile);
            String b64 = java.util.Base64.getEncoder().encodeToString(audioBytes);
            String cqCode = "[CQ:record,file=base64://" + b64 + "]";
            bot.sendGroupReply(Long.parseLong(groupId), cqCode);

            // 发送后删除临时文件
            try { java.nio.file.Files.delete(audioFile); } catch (Exception ignored) {}

            bot.getBaiLianService().getBotMemory().record(
                    groupId,
                    com.start.service.BotMemoryService.EntryType.VOICE,
                    null,
                    text
            );

            return "已发送语音: " + text;
        } catch (Exception e) {
            return "语音发送失败: " + e.getMessage();
        }
    }
}
