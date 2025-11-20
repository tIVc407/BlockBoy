package de.tomalbrc.blockboy.emulator;

import com.sun.jna.*;
import com.sun.jna.Callback;
import java.util.Arrays;
import java.util.List;

/**
 * LibRetro JNA Interface for BlockBoy
 *
 * JNA bindings to the mgba_libretro native library.
 * Provides the complete libretro API for GBA/GB/GBC emulation.
 */
public interface LibRetro extends Library {

    // Callback interfaces
    interface VideoRefreshCallback extends Callback {
        void invoke(Pointer data, int width, int height, int pitch);
    }

    interface AudioSampleCallback extends Callback {
        void invoke(short left, short right);
    }

    interface AudioSampleBatchCallback extends Callback {
        int invoke(Pointer data, int frames);
    }

    interface InputPollCallback extends Callback {
        void invoke();
    }

    interface InputStateCallback extends Callback {
        short invoke(int port, int device, int index, int id);
    }

    interface EnvironmentCallback extends Callback {
        boolean invoke(int cmd, Pointer data);
    }

    // Data structures
    class GameInfo extends Structure {
        public String path;
        public Pointer data;
        public long size;
        public String meta;

        public static class ByReference extends GameInfo implements Structure.ByReference { }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("path", "data", "size", "meta");
        }
    }

    class Geometry extends Structure {
        public int base_width;
        public int base_height;
        public int max_width;
        public int max_height;
        public double aspect_ratio;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("base_width", "base_height", "max_width", "max_height", "aspect_ratio");
        }
    }

    class Timing extends Structure {
        public double fps;
        public double sample_rate;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("fps", "sample_rate");
        }
    }

    class SystemAvInfo extends Structure {
        public Geometry geometry;
        public Timing timing;

        public static class ByReference extends SystemAvInfo implements Structure.ByReference { }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("geometry", "timing");
        }
    }

    class SystemInfo extends Structure {
        public String library_name;
        public String library_version;
        public String valid_extensions;
        public boolean need_fullpath;
        public boolean block_extract;

        public static class ByReference extends SystemInfo implements Structure.ByReference { }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("library_name", "library_version", "valid_extensions", "need_fullpath", "block_extract");
        }
    }

    // Core functions
    int retro_api_version();

    void retro_get_system_info(SystemInfo.ByReference info);

    void retro_get_system_av_info(SystemAvInfo.ByReference info);

    void retro_init();

    void retro_deinit();

    boolean retro_load_game(GameInfo.ByReference game);

    void retro_unload_game();

    void retro_run();

    // Callback registration
    void retro_set_video_refresh(VideoRefreshCallback cb);

    void retro_set_audio_sample(AudioSampleCallback cb);

    void retro_set_audio_sample_batch(AudioSampleBatchCallback cb);

    void retro_set_input_poll(InputPollCallback cb);

    void retro_set_input_state(InputStateCallback cb);

    void retro_set_environment(EnvironmentCallback cb);

    // Save/Load states
    long retro_serialize_size();

    boolean retro_serialize(Pointer data, long size);

    boolean retro_unserialize(Pointer data, long size);

    // Memory
    Pointer retro_get_memory_data(int id);

    long retro_get_memory_size(int id);
}
