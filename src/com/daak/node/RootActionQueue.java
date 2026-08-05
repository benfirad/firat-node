package com.daak.node;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

final class RootActionQueue {
    private RootActionQueue() {}

    static boolean request(Context context, String action) {
        if (!allowed(action)) return false;
        synchronized (RootActionQueue.class) {
            File queue = new File(context.getFilesDir(), "daak-node");
            File temp = new File(queue, "cleanup.request.tmp");
            File target = new File(queue, "cleanup.request");
            try {
                if (!queue.exists() && !queue.mkdirs()) return false;
                FileOutputStream output = new FileOutputStream(temp, false);
                output.write(action.getBytes(Charset.forName("UTF-8")));
                output.flush();
                output.getFD().sync();
                output.close();
                if (target.exists() && !target.delete()) return false;
                if (!temp.renameTo(target)) { temp.delete(); return false; }
                return true;
            } catch (Exception error) {
                temp.delete();
                return false;
            }
        }
    }

    private static boolean allowed(String action) {
        return "keenetic-wol".equals(action) || "keenetic-wol-dry-run".equals(action) ||
                "ru.tech.imageresizershrinker".equals(action) ||
                "me.zhanghai.android.files".equals(action) ||
                "com.google.android.apps.photos".equals(action);
    }
}
