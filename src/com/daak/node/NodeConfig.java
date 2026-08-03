package com.daak.node;

import android.content.Context;

import java.io.File;
import java.io.FileReader;
import java.util.Properties;

final class NodeConfig {
    private NodeConfig() { }

    static String get(Context context, String key, String fallback) {
        File[] candidates = {
                new File(context.getFilesDir(), "config.properties"),
                new File("/sdcard/Download/daak-node/config.properties")
        };
        for (File file : candidates) {
            Properties props = new Properties();
            try {
                FileReader reader = new FileReader(file);
                props.load(reader);
                reader.close();
                String value = props.getProperty(key);
                if (value != null && value.trim().length() > 0) return value.trim();
            } catch (Exception ignored) { }
        }
        return fallback;
    }
}
