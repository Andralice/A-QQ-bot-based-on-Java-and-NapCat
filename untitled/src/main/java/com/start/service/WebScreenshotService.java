
package com.start.service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
//✅截图服务（已完成）
public class WebScreenshotService {

    // ✅ 指向虚拟环境中的 Python 解释器
    private static final String PYTHON_EXECUTABLE = "/home/alice/py/bin/python";
    // ✅ 脚本放在 JAR 同级目录
    private static final String SCRIPT_NAME = "screenshot.py";

    // ✅ 动态生成唯一输出文件名，避免并发冲突
    private static String generateOutputPath() {
        return "/tmp/screenshot_" + UUID.randomUUID().toString().replace("-", "") + ".png";
    }

    /**
     * 执行截图任务
     * @param taskName 任务名（如 "kkrb-overview"）
     * @return 图片文件路径
     */
    public CompletableFuture<String> takeScreenshot(String taskName) {
        String outputPath = generateOutputPath();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // ✅ 获取 JAR 所在目录（即 screenshot.py 所在目录）
                String jarDir = getJarDirectory();
                ProcessBuilder pb = new ProcessBuilder(PYTHON_EXECUTABLE, SCRIPT_NAME, taskName, outputPath);
                pb.directory(new File(jarDir)); // 在脚本所在目录执行

                Process process = pb.start();

                // 读取标准输出（用于调试）
                BufferedReader stdout = new BufferedReader(
                        new InputStreamReader(process.getInputStream())
                );
                BufferedReader stderr = new BufferedReader(
                        new InputStreamReader(process.getErrorStream())
                );

                StringBuilder output = new StringBuilder();
                String line;
                while ((line = stdout.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = stderr.readLine()) != null) {
                    output.append("[ERROR] ").append(line).append("\n");
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Python script failed with code " + exitCode +
                            ". Output: " + output.toString());
                }

                // ✅ 验证文件是否生成
                if (!Files.exists(Paths.get(outputPath))) {
                    throw new RuntimeException("Output file not created: " + outputPath);
                }

                return outputPath;
            } catch (Exception e) {
                // 清理可能残留的空文件
                try {
                    Files.deleteIfExists(Paths.get(outputPath));
                } catch (IOException ignored) {}
                throw new RuntimeException("Failed to take screenshot for task: " + taskName, e);
            }
        });
    }

    /**
     * 获取当前 JAR 文件所在目录
     */
    private String getJarDirectory() {
        try {
            String path = WebScreenshotService.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();
            File jarFile = new File(path);
            return jarFile.getParentFile().getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("Cannot determine JAR directory", e);
        }
    }

    /**
     * 安全读取图片字节（并自动清理文件）
     */
    public byte[] readAndCleanupImage(String imagePath) throws IOException {
        try {
            Path path = Paths.get(imagePath);
            byte[] bytes = Files.readAllBytes(path);
            Files.deleteIfExists(path); // ✅ 发送后立即清理
            return bytes;
        } catch (IOException e) {
            // 如果文件不存在，可能是已被清理或从未生成
            throw new IOException("Cannot read image: " + imagePath, e);
        }
    }
}