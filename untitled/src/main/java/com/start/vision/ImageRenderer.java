package com.start.vision;

// ImageRenderer.java
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ImageRenderer {

    private static volatile ImageRenderer instance;
    private final Map<String, Font> fontCache = new ConcurrentHashMap<>();
    private final Path assetDir;

    // 单例
    public static ImageRenderer getInstance() {
        if (instance == null) {
            synchronized (ImageRenderer.class) {
                if (instance == null) {
                    // 默认资源目录：项目根目录下的 assets 文件夹
                    instance = new ImageRenderer("assets");
                }
            }
        }
        return instance;
    }

    // 可指定资源目录
    public ImageRenderer(String assetPath) {
        this.assetDir = Paths.get(assetPath).toAbsolutePath();
        if (!Files.exists(this.assetDir)) {
            System.err.println("警告：资源目录不存在: " + this.assetDir);
        }
    }

    // 渲染模板并返回 Base64 字符串（方便 OneBot 发图）
    public String renderToBase64(ImageTemplate<?> template, Object data) {
        try {
            BufferedImage image = template.render(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // 加载字体（带缓存）
    public Font loadFont(String fontFileName, float size) {
        String key = fontFileName + "@" + size;
        return fontCache.computeIfAbsent(key, k -> {
            try {
                // 从 classpath 的 /assets/fonts/ 目录下加载字体文件
                String resourcePath = "assets/fonts/" + fontFileName;
                InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);

                if (is == null) {
                    System.err.println("字体资源未找到: " + resourcePath + "（请确保文件在 src/main/resources/assets/fonts/ 下）");
                    return new Font(Font.SANS_SERIF, Font.PLAIN, (int) size);
                }

                // 使用 try-with-resources 确保流关闭（Font.createFont 会读取全部数据，之后流可关闭）
                try (InputStream fontStream = is) {
                    Font baseFont = Font.createFont(Font.TRUETYPE_FONT, fontStream);
                    return baseFont.deriveFont(size);
                }
            } catch (Exception e) {
                System.err.println("加载字体失败: " + fontFileName + ", 使用默认字体");
                e.printStackTrace();
                return new Font(Font.SANS_SERIF, Font.PLAIN, (int) size);
            }
        });
    }

    // 工具方法：居中绘制字符串
    public void drawCenteredString(Graphics2D g, String text, int panelWidth, int y) {
        FontMetrics fm = g.getFontMetrics();
        int x = (panelWidth - fm.stringWidth(text)) / 2;
        g.drawString(text, x, y);
    }
}