package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class BaiLianService {
    private static final Logger logger = LoggerFactory.getLogger(BaiLianService.class);

    // 👇 替换为你的百炼 API Key
    private static final String API_KEY = "sk-86b180d2f5254cb9b7c37af1f442baaf";

    // 👇 百炼的 API 地址（通常类似）
    private static final String ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    public String generate(String prompt) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(ENDPOINT);

            // 添加 Header
            post.setHeader(new BasicHeader("Authorization", "Bearer " + API_KEY));
            post.setHeader("Content-Type", "application/json");

            // 请求体
            Map<String, Object> body = new HashMap<>();
            body.put("model", "qwen-max"); // 可选：qwen-plus, qwen-turbo
            body.put("input", Map.of("messages", new Object[]{
                    Map.of("role", "user", "content", prompt)
            }));

            post.setEntity(new StringEntity(new ObjectMapper().writeValueAsString(body), ContentType.APPLICATION_JSON));

            var response = client.execute(post);
            String result = new String(response.getEntity().getContent().readAllBytes());
            return extractText(result); // 解析返回 JSON 中的 content
        } catch (Exception e) {
            logger.error("调用百炼 AI 失败", e);
            return "呜...AI 罢工了，请稍后再试~";
        }
    }

    private String extractText(String json) {
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            return node.path("output").path("text").asText();
        } catch (Exception e) {
            return "AI 返回异常：" + json;
        }
    }
}