package com.start.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图像渲染基础工具类
 * <p>
 * 职责：提供底层绘图能力（字体加载、图片加载、文本绘制、Base64 转换等）
 * 特点：无业务逻辑，纯粹的工具方法集合，可被任意业务类复用
 */
public class ImageRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ImageRenderer.class);
    private static volatile ImageRenderer instance;
    private final Map<String, Font> fontCache = new ConcurrentHashMap<>();

    // 单例
    public static ImageRenderer getInstance() {
        if (instance == null) {
            synchronized (ImageRenderer.class) {
                if (instance == null) {
                    instance = new ImageRenderer();
                }
            }
        }
        return instance;
    }

    public ImageRenderer() {}

    // ==================== 资源加载 ====================

    /**
     * 加载字体（带缓存）
     * @param fontFileName 字体文件名（如 "HarmonyOS_SansSC_Bold.ttf"）
     * @param size 字号
     * @return Font 对象
     */
    public Font loadFont(String fontFileName, float size) {
        String key = fontFileName + "@" + size;
        return fontCache.computeIfAbsent(key, k -> {
            try {
                String resourcePath = "assets/fonts/" + fontFileName;
                InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
                if (is == null) {
                    logger.warn("字体资源未找到: {}", resourcePath);
                    return new Font(Font.SANS_SERIF, Font.PLAIN, (int) size);
                }
                try (InputStream fontStream = is) {
                    Font baseFont = Font.createFont(Font.TRUETYPE_FONT, fontStream);
                    return baseFont.deriveFont(size);
                }
            } catch (Exception e) {
                logger.error("加载字体失败: {}", fontFileName, e);
                return new Font(Font.SANS_SERIF, Font.PLAIN, (int) size);
            }
        });
    }

    /**
     * 加载图片（支持 classpath、URL、本地文件）
     * @param path 图片路径
     * @return BufferedImage，加载失败返回 null
     */
    public BufferedImage loadImage(String path) {
        try {
            // 1. 尝试从 Classpath 加载
            InputStream is = getClass().getClassLoader().getResourceAsStream(path);
            if (is != null) {
                return ImageIO.read(is);
            }
            // 2. 尝试作为 URL 加载
            if (path.startsWith("http")) {
                return ImageIO.read(new URL(path));
            }
            // 3. 尝试作为本地文件加载
            File file = new File(path);
            if (file.exists()) {
                return ImageIO.read(file);
            }
        } catch (Exception e) {
            logger.error("加载图片异常: {}", path, e);
        }
        return null;
    }

    // ==================== 绘图工具 ====================

    /**
     * 在指定位置绘制文本
     * @param g Graphics2D 对象
     * @param text 要绘制的文本
     * @param x X 坐标
     * @param y Y 坐标
     */
    public void drawText(Graphics2D g, String text, int x, int y) {
        if (text != null && !text.isEmpty()) {
            g.drawString(text, x, y);
        }
    }

    /**
     * 居中绘制文本
     * @param g Graphics2D 对象
     * @param text 要绘制的文本
     * @param panelWidth 画布宽度
     * @param y Y 坐标
     */
    public void drawCenteredString(Graphics2D g, String text, int panelWidth, int y) {
        if (text != null && !text.isEmpty()) {
            FontMetrics fm = g.getFontMetrics();
            int x = (panelWidth - fm.stringWidth(text)) / 2;
            g.drawString(text, x, y);
        }
    }

    /**
     * 绘制水平分隔线
     * @param g Graphics2D 对象
     * @param x1 起点 X
     * @param y 起点/终点 Y
     * @param x2 终点 X
     * @param color 线条颜色
     * @param strokeWidth 线条宽度
     */
    public void drawHorizontalLine(Graphics2D g, int x1, int y, int x2, Color color, float strokeWidth) {
        Color originalColor = g.getColor();
        Stroke originalStroke = g.getStroke();
        
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeWidth));
        g.drawLine(x1, y, x2, y);
        
        g.setColor(originalColor);
        g.setStroke(originalStroke);
    }

    /**
     * 绘制圆角矩形边框
     * @param g Graphics2D 对象
     * @param x X 坐标
     * @param y Y 坐标
     * @param width 宽度
     * @param height 高度
     * @param arcWidth 圆角宽度
     * @param arcHeight 圆角高度
     * @param color 边框颜色
     * @param strokeWidth 边框宽度
     */
    public void drawRoundRectBorder(Graphics2D g, int x, int y, int width, int height, 
                                     int arcWidth, int arcHeight, Color color, float strokeWidth) {
        Color originalColor = g.getColor();
        Stroke originalStroke = g.getStroke();
        
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeWidth));
        g.drawRoundRect(x, y, width, height, arcWidth, arcHeight);
        
        g.setColor(originalColor);
        g.setStroke(originalStroke);
    }

    /**
     * 在指定区域绘制图像（自动缩放）
     * @param g Graphics2D 对象
     * @param image 要绘制的图像
     * @param x X 坐标
     * @param y Y 坐标
     * @param width 目标宽度
     * @param height 目标高度
     */
    public void drawImage(Graphics2D g, BufferedImage image, int x, int y, int width, int height) {
        if (image != null) {
            g.drawImage(image, x, y, width, height, null);
        }
    }

    /**
     * 配置抗锯齿等渲染提示（建议在创建 Graphics2D 后立即调用）
     * @param g Graphics2D 对象
     */
    public void configureRenderingHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    // ==================== 输出转换 ====================

    /**
     * 将 BufferedImage 转换为 Base64 字符串
     * @param image 要转换的图像
     * @param format 图片格式（如 "png", "jpg"）
     * @return Base64 字符串，转换失败返回 null
     */
    public String toBase64(BufferedImage image, String format) {
        if (image == null) {
            logger.error("图像为 null，无法转换 Base64");
            return null;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, format, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            logger.error("转换为 Base64 失败", e);
            return null;
        }
    }

    /**
     * 兼容旧版模板渲染方法（保留以兼容 CpResultTemplate 等已有代码）
     * @param template 图像模板
     * @param data 数据对象
     * @return Base64 字符串
     */
    public String renderToBase64(ImageTemplate<?> template, Object data) {
        try {
            BufferedImage image = template.render(data);
            return toBase64(image, "png");
        } catch (Exception e) {
            logger.error("渲染图像失败", e);
            return null;
        }
    }
}