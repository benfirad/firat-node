package com.daak.node;

public final class RemoteRoutingTest {
    public static void main(String[] args) {
        String id = "82d0ecf8-834e-3bb6-7122-a73a08baf040";
        String expected = "https://remotedesktop.google.com/access/session/" + id;
        if (!expected.equals(RemoteRouting.sessionUrl("  " + id.toUpperCase() + "  "))) {
            throw new AssertionError("valid host UUID did not produce canonical session URL");
        }
        if (RemoteRouting.sessionUrl("") != null) {
            throw new AssertionError("empty host ID must fall back to the device list");
        }
        if (RemoteRouting.sessionUrl("https://evil.example/session") != null) {
            throw new AssertionError("non-UUID route must be rejected");
        }
    }
}
