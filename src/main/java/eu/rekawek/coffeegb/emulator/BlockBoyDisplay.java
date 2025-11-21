package eu.rekawek.coffeegb.emulator;

import com.mojang.logging.LogUtils;
import eu.pb4.mapcanvas.api.core.CanvasImage;
import eu.pb4.mapcanvas.api.utils.CanvasUtils;
import eu.rekawek.coffeegb.gpu.Display;
import com.sun.jna.Pointer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BlockBoyDisplay implements Display, Runnable {

    private final BufferedImage img;

    public static final int[] COLORS = new int[]{0xe6f8da, 0x99c886, 0x437969, 0x051f2a};

    public static final int[] COLORS_GRAYSCALE = new int[]{0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000};

    private final int[] rgb;

    private final int[] waitingFrame;

    private boolean enabled;


    private volatile boolean doStop;

    private volatile boolean isStopped;

    private boolean frameIsWaiting;

    private volatile boolean grayscale;

    private int pos;

    // Frame queue for non-blocking rendering
    private final ConcurrentLinkedQueue<FrameData> frameQueue = new ConcurrentLinkedQueue<>();

    /**
     * Holds raw frame data from LibRetro callback
     * Converted to XRGB8888 asynchronously by display thread
     */
    private static class FrameData {
        final byte[] data;
        final int width;
        final int height;
        final int pitch;

        FrameData(byte[] data, int width, int height, int pitch) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.pitch = pitch;
        }
    }

    public BlockBoyDisplay(int scale, boolean grayscale) {
        super();

        img = new BufferedImage(DISPLAY_WIDTH, DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB);
        rgb = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];
        waitingFrame = new int[rgb.length];
        this.grayscale = grayscale;
    }

    @Override
    public void putDmgPixel(int color) {
        rgb[pos++] = grayscale ? COLORS_GRAYSCALE[color] : COLORS[color];
        pos = pos % rgb.length;
    }

    @Override
    public void putColorPixel(int gbcRgb) {
        rgb[pos++] = Display.translateGbcRgb(gbcRgb);
    }

    @Override
    public synchronized void frameIsReady() {
        pos = 0;
        if (frameIsWaiting) {
            return;
        }
        frameIsWaiting = true;
        System.arraycopy(rgb, 0, waitingFrame, 0, rgb.length);
        notify();
    }

    @Override
    public void enableLcd() {
        enabled = true;
    }

    @Override
    public void disableLcd() {
        enabled = false;
    }

    @Override
    public void run() {
        doStop = false;
        isStopped = false;
        frameIsWaiting = false;
        enabled = true;
        pos = 0;

        LogUtils.getLogger().info("[BlockBoyDisplay] Display thread started (using queue for non-blocking rendering)");
        int frameCount = 0;

        while (!doStop) {
            // Process any queued frames from LibRetro callback
            FrameData frameData = frameQueue.poll();
            if (frameData != null) {
                try {
                    // Convert RGB565 to XRGB8888 asynchronously on display thread
                    convertAndUpdateFrame(frameData);
                    frameCount++;
                    if (frameCount % 60 == 0) {
                        LogUtils.getLogger().info("[BlockBoyDisplay] Converted and rendered " + frameCount + " frames");
                    }
                } catch (Exception e) {
                    LogUtils.getLogger().error("[BlockBoyDisplay] Error converting frame", e);
                }
            } else {
                // No frames in queue, sleep briefly to avoid busy-waiting
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        isStopped = true;
        LogUtils.getLogger().info("[BlockBoyDisplay] Display thread stopped after " + frameCount + " frames");
    }

    /**
     * Convert raw RGB565 frame data to XRGB8888 and update display
     * Called by display thread asynchronously
     */
    private void convertAndUpdateFrame(FrameData frameData) {
        int pixelIndex = 0;
        byte[] scanlineData = new byte[frameData.pitch];

        for (int y = 0; y < frameData.height; y++) {
            System.arraycopy(frameData.data, y * frameData.pitch, scanlineData, 0, frameData.pitch);

            for (int x = 0; x < frameData.width; x++) {
                int bytePos = x * 2;
                if (bytePos + 1 < scanlineData.length) {
                    int rgb565 = ((scanlineData[bytePos + 1] & 0xFF) << 8) | (scanlineData[bytePos] & 0xFF);

                    int r5 = (rgb565 >> 11) & 0x1F;
                    int g6 = (rgb565 >> 5) & 0x3F;
                    int b5 = rgb565 & 0x1F;

                    // Expand 5-bit/6-bit to 8-bit
                    int r8 = (r5 << 3) | (r5 >> 2);
                    int g8 = (g6 << 2) | (g6 >> 4);
                    int b8 = (b5 << 3) | (b5 >> 2);

                    if (pixelIndex < rgb.length) {
                        rgb[pixelIndex++] = (r8 << 16) | (g8 << 8) | b8;
                    }
                }
            }
        }

        // Update the display image
        synchronized (this) {
            if (!frameIsWaiting) {
                System.arraycopy(rgb, 0, waitingFrame, 0, rgb.length);
                img.setRGB(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, waitingFrame, 0, DISPLAY_WIDTH);
            }
        }
    }

    public void stop() {
        doStop = true;
        synchronized (this) {
            while (!isStopped) {
                try {
                    wait(10);
                } catch (InterruptedException e) {
                    LogUtils.getLogger().warn("Received Interruption trying to end emulation");
                }
            }
        }
    }

    public CanvasImage render(int width, int height) {
        Image resizedImage = this.img.getScaledInstance(width, height, Image.SCALE_FAST);
        BufferedImage resized = convertToBufferedImage(resizedImage);
        int[][] pixels = convertPixelArray(resized);

        var state = new CanvasImage(width, height);

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                state.set(i, j, CanvasUtils.findClosestColorARGB(pixels[j][i]));
            }
        }

        return state;
    }

    private static int clamp(int i, int min, int max) {
        if (min > max)
            throw new IllegalArgumentException("max value cannot be less than min value");
        if (i < min)
            return min;
        if (i > max)
            return max;
        return i;
    }

    private static int[][] convertPixelArray(BufferedImage image) {

        final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        final int width = image.getWidth();
        final int height = image.getHeight();

        int[][] result = new int[height][width];
        final int pixelLength = 4;
        for (int pixel = 0, row = 0, col = 0; pixel + 3 < pixels.length; pixel += pixelLength) {
            int argb = 0;
            argb += (((int) pixels[pixel] & 0xff) << 24); // alpha
            argb += ((int) pixels[pixel + 1] & 0xff); // blue
            argb += (((int) pixels[pixel + 2] & 0xff) << 8); // green
            argb += (((int) pixels[pixel + 3] & 0xff) << 16); // red
            result[row][col] = argb;
            col++;
            if (col == width) {
                col = 0;
                row++;
            }
        }

        return result;
    }

    private static BufferedImage convertToBufferedImage(Image image) {
        BufferedImage newImage = new BufferedImage(image.getWidth(null), image.getHeight(null),
                BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = newImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return newImage;
    }

    /**
     * Callback from LibRetro when a frame is ready (RGB565 format)
     * NON-BLOCKING: Queues raw frame data for asynchronous conversion by display thread
     * This prevents blocking the emulation thread on expensive RGB565→XRGB8888 conversion
     */
    public void onFrameRendered(Pointer data, int width, int height, int pitch) {
        if (data == null || data.equals(Pointer.NULL)) {
            return;
        }

        try {
            // Copy raw frame data from native pointer to byte array
            byte[] frameBytes = new byte[height * pitch];
            data.read(0, frameBytes, 0, frameBytes.length);

            // Queue for asynchronous processing by display thread
            frameQueue.offer(new FrameData(frameBytes, width, height, pitch));

        } catch (Exception e) {
            LogUtils.getLogger().error("Error in onFrameRendered: " + e.getMessage(), e);
        }
    }
}
