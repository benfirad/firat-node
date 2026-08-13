package com.daak.node.tools;

import android.os.IBinder;
import android.os.ResultReceiver;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Small root-side controller for Android 10's hidden tethering API.
 *
 * It deliberately resolves every hidden framework class and method at runtime,
 * so the shipped DEX does not depend on Samsung's private framework stubs.
 */
public final class GatewayControl {
    private static final int TETHERING_WIFI = 0;
    private static final String CALLING_PACKAGE = "com.android.shell";

    private GatewayControl() {}

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "status" : args[0];
        Object connectivity = connectivityService();

        if ("inspect".equals(command)) {
            inspect(connectivity);
        } else if ("status".equals(command)) {
            status(connectivity);
        } else if ("start".equals(command)) {
            invokeTethering(connectivity, "startTethering");
            Thread.sleep(1500L);
            status(connectivity);
        } else if ("stop".equals(command)) {
            invokeTethering(connectivity, "stopTethering");
            Thread.sleep(500L);
            status(connectivity);
        } else {
            throw new IllegalArgumentException("usage: inspect|status|start|stop");
        }
    }

    private static Object connectivityService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "connectivity");
        if (binder == null) {
            throw new IllegalStateException("connectivity binder unavailable");
        }

        Class<?> stub = Class.forName("android.net.IConnectivityManager$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        return asInterface.invoke(null, binder);
    }

    private static void inspect(Object connectivity) {
        for (Method method : connectivity.getClass().getMethods()) {
            String name = method.getName().toLowerCase();
            if ((name.contains("tether") || name.contains("provision"))
                    && Modifier.isPublic(method.getModifiers())) {
                System.out.println(method.toString());
            }
        }
    }

    private static void status(Object connectivity) throws Exception {
        Method method = findMethod(connectivity, "getTetheredIfaces");
        Object result = method.invoke(connectivity, argumentsFor(method));
        int length = result != null && result.getClass().isArray() ? Array.getLength(result) : 0;
        System.out.println(length > 0 ? "active" : "inactive");
        for (int i = 0; i < length; i++) {
            System.out.println("interface=" + Array.get(result, i));
        }
    }

    private static void invokeTethering(Object connectivity, String name) throws Exception {
        Method method = findMethod(connectivity, name);
        method.invoke(connectivity, argumentsFor(method));
    }

    private static Method findMethod(Object connectivity, String name) {
        Method best = null;
        for (Method method : connectivity.getClass().getMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            if (best == null || method.getParameterTypes().length > best.getParameterTypes().length) {
                best = method;
            }
        }
        if (best == null) {
            throw new IllegalStateException(name + " unavailable on this Android build");
        }
        return best;
    }

    private static Object[] argumentsFor(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == int.class || type == Integer.class) {
                args[i] = TETHERING_WIFI;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == String.class) {
                args[i] = CALLING_PACKAGE;
            } else if (ResultReceiver.class.isAssignableFrom(type)) {
                args[i] = new ResultReceiver(null) {};
            } else {
                throw new IllegalStateException("unsupported " + method.getName()
                        + " parameter: " + type.getName());
            }
        }
        return args;
    }
}
