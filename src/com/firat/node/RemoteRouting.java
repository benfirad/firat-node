package com.firat.node;

import java.util.UUID;

final class RemoteRouting {
    private static final String SESSION_PREFIX =
            "https://remotedesktop.google.com/access/session/";

    private RemoteRouting() { }

    static String sessionUrl(String configuredHostId) {
        if (configuredHostId == null) return null;
        try {
            return SESSION_PREFIX + UUID.fromString(configuredHostId.trim()).toString();
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
