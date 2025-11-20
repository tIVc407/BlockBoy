package de.tomalbrc.blockboy.emulator;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.mojang.logging.LogUtils;
import de.tomalbrc.blockboy.BlockBoy;
import eu.rekawek.coffeegb.CartridgeOptions;
import eu.rekawek.coffeegb.controller.ButtonListener;
import eu.rekawek.coffeegb.emulator.BlockBoyDisplay;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LibRetro-based Emulation Controller for BlockBoy
 *
 * Replaces CoffeeGB with the native mgba_libretro library via JNA.
 * Provides full GBA/GB/GBC support with 60 FPS performance.
 */
public class LibRetroEmulationController {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[BlockBoy-LibRetro] ";

    private final BlockBoyDisplay display;
    private final File currentRom;
    private final ServerPlayer player;
    private final CartridgeOptions options;

    private LibRetro libretro;
    private Thread emulationThread;
    private volatile boolean isRunning = false;
    private volatile boolean shouldStop = false;

    // Input state (thread-safe bitmask)
    private final AtomicInteger buttonMask = new AtomicInteger(0);

    // LibRetro Constants
    private static final int RETRO_DEVICE_JOYPAD = 1;
    private static final int RETRO_ENVIRONMENT_SET_PIXEL_FORMAT = 10;
    private static final int RETRO_PIXEL_FORMAT_XRGB8888 = 1;

    // GBA Button IDs (LibRetro standard)
    public static final int BUTTON_B = 0;
    public static final int BUTTON_A = 8;
    public static final int BUTTON_SELECT = 2;
    public static final int BUTTON_START = 3;
    public static final int BUTTON_UP = 4;
    public static final int BUTTON_DOWN = 5;
    public static final int BUTTON_LEFT = 6;
    public static final int BUTTON_RIGHT = 7;
    public static final int BUTTON_L = 10;
    public static final int BUTTON_R = 11;

    public LibRetroEmulationController(CartridgeOptions options, File initialRom, ServerPlayer player) {
        this.options = options;
        this.currentRom = initialRom;
        this.player = player;
        this.display = new BlockBoyDisplay(1, false);
    }

    public BlockBoyDisplay getDisplay() {
        return display;
    }

    /**
     * Start emulation with LibRetro
     */
    public void startEmulation() {
        try {
            // Load native library
            libretro = Native.load("C:/Users/tommy/Desktop/BlockBoy/mgba_libretro", LibRetro.class);
            LOGGER.info(TAG + "LibRetro loaded successfully");

            // Setup callbacks BEFORE init
            setupCallbacks();

            // Initialize core
            libretro.retro_init();
            LOGGER.info(TAG + "LibRetro core initialized");

            // Load ROM
            LibRetro.GameInfo.ByReference gameInfo = new LibRetro.GameInfo.ByReference();
            gameInfo.path = currentRom.getAbsolutePath();
            gameInfo.data = null;  // Let core load from path
            gameInfo.size = 0;

            if (!libretro.retro_load_game(gameInfo)) {
                throw new RuntimeException("Failed to load ROM: " + currentRom.getAbsolutePath());
            }

            LOGGER.info(TAG + "ROM loaded: " + currentRom.getName());

            // Get system info
            LibRetro.SystemInfo.ByReference sysInfo = new LibRetro.SystemInfo.ByReference();
            libretro.retro_get_system_info(sysInfo);
            LOGGER.info(TAG + "Core: " + sysInfo.library_name + " " + sysInfo.library_version);

            // Get AV info
            LibRetro.SystemAvInfo.ByReference avInfo = new LibRetro.SystemAvInfo.ByReference();
            libretro.retro_get_system_av_info(avInfo);
            LOGGER.info(TAG + "Resolution: " + avInfo.geometry.base_width + "x" + avInfo.geometry.base_height);
            LOGGER.info(TAG + "FPS: " + String.format("%.3f", avInfo.timing.fps));

            // Start display thread
            new Thread(display, "BlockBoy-Display").start();

            // Start emulation thread
            shouldStop = false;
            isRunning = true;
            emulationThread = new Thread(this::emulationLoop, "BlockBoy-Emulation");
            emulationThread.start();

            LOGGER.info(TAG + "Emulation started");

        } catch (Exception e) {
            LOGGER.error(TAG + "Failed to start emulation", e);
            stopEmulation();
        }
    }

    /**
     * Main emulation loop - runs on background thread
     */
    private void emulationLoop() {
        try {
            while (!shouldStop && isRunning) {
                libretro.retro_run();
                Thread.sleep(16);  // ~60 FPS
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error(TAG + "Emulation loop error", e);
        } finally {
            isRunning = false;
        }
    }

    /**
     * Setup all LibRetro callbacks
     */
    private void setupCallbacks() {
        // Video refresh: send pixels to BlockBoyDisplay
        libretro.retro_set_video_refresh((data, width, height, pitch) -> {
            if (display != null) {
                display.onFrameRendered(data, width, height, pitch);
            }
        });

        // Input poll: no-op, we update instantly via KeyListener
        libretro.retro_set_input_poll(() -> {});

        // Input state: query button mask
        libretro.retro_set_input_state((port, device, index, id) -> {
            if (port == 0 && device == RETRO_DEVICE_JOYPAD) {
                int mask = buttonMask.get();
                boolean isPressed = (mask & (1 << id)) != 0;
                return (short) (isPressed ? 1 : 0);
            }
            return 0;
        });

        // Audio sample (single): stub for now
        libretro.retro_set_audio_sample((left, right) -> {});

        // Audio batch: stub for now
        libretro.retro_set_audio_sample_batch((data, frames) -> frames);

        // Environment: accept pixel format negotiation
        libretro.retro_set_environment((cmd, data) -> {
            if (cmd == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT) {
                return true;  // Accept format
            }
            return false;
        });
    }

    /**
     * Stop emulation and cleanup
     */
    public void stopEmulation() {
        if (!isRunning) {
            return;
        }

        shouldStop = true;
        isRunning = false;

        // Wait for emulation thread
        if (emulationThread != null && emulationThread.isAlive()) {
            try {
                emulationThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Cleanup LibRetro
        if (libretro != null) {
            try {
                libretro.retro_unload_game();
                libretro.retro_deinit();
            } catch (Exception e) {
                LOGGER.error(TAG + "Error during LibRetro cleanup", e);
            }
        }

        // Stop display
        display.stop();

        LOGGER.info(TAG + "Emulation stopped");
    }

    /**
     * Set button state from Minecraft input
     *
     * @param buttonId GBA button ID (BUTTON_A, BUTTON_B, etc.)
     * @param pressed  true if pressed, false if released
     */
    public void setButtonState(int buttonId, boolean pressed) {
        if (pressed) {
            buttonMask.updateAndGet(mask -> mask | (1 << buttonId));
        } else {
            buttonMask.updateAndGet(mask -> mask & ~(1 << buttonId));
        }
    }

    /**
     * Handle button press from CoffeeGB ButtonListener interface
     */
    public void pressed(ButtonListener.Button button) {
        int buttonId = coffeegbToLibretro(button);
        if (buttonId != -1) {
            setButtonState(buttonId, true);
        }
    }

    /**
     * Handle button release from CoffeeGB ButtonListener interface
     */
    public void released(ButtonListener.Button button) {
        int buttonId = coffeegbToLibretro(button);
        if (buttonId != -1) {
            setButtonState(buttonId, false);
        }
    }

    /**
     * Convert CoffeeGB button enum to LibRetro button ID
     */
    private int coffeegbToLibretro(ButtonListener.Button button) {
        return switch (button) {
            case A -> BUTTON_A;
            case B -> BUTTON_B;
            case START -> BUTTON_START;
            case SELECT -> BUTTON_SELECT;
            case UP -> BUTTON_UP;
            case DOWN -> BUTTON_DOWN;
            case LEFT -> BUTTON_LEFT;
            case RIGHT -> BUTTON_RIGHT;
        };
    }
}
