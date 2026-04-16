package com.start.vision;

// ImageTemplate.java
import java.awt.image.BufferedImage;

@FunctionalInterface
public interface ImageTemplate<T> {
    BufferedImage render(Object data);
}
