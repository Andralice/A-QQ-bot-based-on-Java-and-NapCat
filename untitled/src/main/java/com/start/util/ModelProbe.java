package com.start.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM endpoint identity probe — detects model impersonation and token inflation.
 *
 * Usage: run the main() method.
 */
public class ModelProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String PROBE = "What AI model or system are you? Please be specific about your model name, version, and the company that created you.";

    public static void main(String[] args) {
        System.out.println("=" .repeat(60));
        System.out.println("  LLM Endpoint Identity Probe");
        System.out.println("=".repeat(60));

        probe("Chat Model",
                BotConfig.getBaiLianBaseUrl(),
                BotConfig.getBaiLianApiKey(),
                BotConfig.getBaiLianChatModel(),
                BotConfig.getBaiLianTimeoutMs());

        probe("Agent Model",
                BotConfig.getAgentBaseUrl(),
                BotConfig.getAgentApiKey(),
                BotConfig.getAgentModel(),
                BotConfig.getAgentTimeoutMs());
    }

    private static void probe(String label, String baseUrl, String apiKey, String expectedModel, int timeoutMs) {
        System.out.println("\n" + "-".repeat(60));
        System.out.println("[" + label + "]");
        System.out.println("  Expected model : " + expectedModel);
        System.out.println("  Endpoint       : " + baseUrl);
        System.out.println("  API key prefix : " + (apiKey.length() > 8 ? apiKey.substring(0, 8) + "..." : apiKey));

        String url = baseUrl;
        if (!url.endsWith("/chat/completions")) {
            if (url.endsWith("/v1")) url += "/chat/completions";
            else if (!url.endsWith("/v1/chat/completions")) {
                url = url.replaceAll("/+$", "") + "/v1/chat/completions";
            }
        }

        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", PROBE));

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", expectedModel);
            body.put("messages", messages);
            body.put("max_tokens", 256);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            System.out.println("  HTTP status     : " + resp.statusCode() + " (" + elapsed + "ms)");

            if (resp.statusCode() != 200) {
                System.out.println("  ❌ Non-200 response: " + resp.body());
                return;
            }

            JsonNode root = MAPPER.readTree(resp.body());

            // --- identity check ---
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                String content = choices.get(0).path("message").path("content").asText().trim();
                System.out.println("  Response content: " + content);
                System.out.println();

                // detect model identity
                String lower = content.toLowerCase();
                String detected = "UNKNOWN";
                if (lower.contains("claude") || lower.contains("anthropic")) {
                    detected = "Claude / Anthropic";
                } else if (lower.contains("chatgpt") || lower.contains("openai") || lower.contains("gpt")) {
                    detected = "ChatGPT / OpenAI";
                } else if (lower.contains("gemini") || lower.contains("google")) {
                    detected = "Gemini / Google";
                } else if (lower.contains("glm") || lower.contains("chatglm") || lower.contains("zhipu")) {
                    detected = "GLM / Zhipu";
                } else if (lower.contains("deepseek")) {
                    detected = "DeepSeek";
                } else if (lower.contains("qwen") || lower.contains("tongyi")) {
                    detected = "Qwen / Alibaba";
                } else if (lower.contains("minimax")) {
                    detected = "MiniMax";
                }

                System.out.println("  🔍 Detected identity: " + detected);

                boolean mismatch = !expectedModel.toLowerCase().contains(detected.toLowerCase())
                        && !detected.toLowerCase().contains(expectedModel.toLowerCase());
                // fuzzy matching
                if (detected.equals("GLM / Zhipu") && expectedModel.toLowerCase().contains("glm")) mismatch = false;
                if (detected.equals("Gemini / Google") && expectedModel.toLowerCase().contains("gemini")) mismatch = false;
                if (detected.equals("DeepSeek") && expectedModel.toLowerCase().contains("deepseek")) mismatch = false;
                if (detected.equals("Qwen / Alibaba") && expectedModel.toLowerCase().contains("qwen")) mismatch = false;

                if (detected.equals("UNKNOWN")) {
                    System.out.println("  ⚠️  Could not determine identity from response");
                } else if (mismatch) {
                    System.out.println("  🚨 MISMATCH! Expected " + expectedModel + " but endpoint served " + detected);
                } else {
                    System.out.println("  ✅ Identity matches expected model");
                }
            } else {
                System.out.println("  ❌ No choices in response");
            }

            // --- token usage check ---
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                int promptTokens = usage.path("prompt_tokens").asInt();
                int completionTokens = usage.path("completion_tokens").asInt();
                int totalTokens = usage.path("total_tokens").asInt();

                System.out.println();
                System.out.println("  📊 Token usage:");
                System.out.println("     prompt_tokens     : " + promptTokens);
                System.out.println("     completion_tokens : " + completionTokens);
                System.out.println("     total_tokens      : " + totalTokens);

                if (promptTokens > 500) {
                    System.out.println("     🚨 prompt_tokens > 500 — possible token inflation! (expected < 200)");
                } else if (promptTokens > 200) {
                    System.out.println("     ⚠️  prompt_tokens > 200 — slightly high, check if endpoint is injecting hidden content");
                } else {
                    System.out.println("     ✅ prompt_tokens looks normal");
                }
            } else {
                System.out.println("  ⚠️  No 'usage' field in response — endpoint may be hiding token stats");
            }

            // --- raw response dump for manual inspection ---
            System.out.println();
            System.out.println("  📋 Raw response (truncated to 800 chars):");
            String raw = resp.body();
            System.out.println("     " + (raw.length() > 800 ? raw.substring(0, 800) + "..." : raw));

        } catch (Exception e) {
            System.out.println("  ❌ Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
