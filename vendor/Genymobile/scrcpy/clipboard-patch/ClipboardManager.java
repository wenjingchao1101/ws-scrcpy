package com.genymobile.scrcpy.wrappers;

import android.content.ClipData;
import android.content.IOnPrimaryClipChangedListener;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/** WebSocket server clipboard bridge for legacy Android through Android 16. */
public class ClipboardManager {
    private final IInterface manager;
    private static final String PACKAGE = "com.android.shell";

    /** Keep the 3.x ServiceManager contract while resolving hidden APIs reflectively. */
    static ClipboardManager create() {
        try {
            Method getService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "clipboard");
            if (binder == null) {
                return null;
            }
            Class<?> stub = Class.forName("android.content.IClipboard$Stub");
            IInterface manager = (IInterface) stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            return manager == null ? null : new ClipboardManager(manager);
        } catch (ReflectiveOperationException e) {
            Log.e("scrcpy", "Could not create clipboard manager", e);
            return null;
        }
    }

    public ClipboardManager(IInterface manager) {
        this.manager = manager;
    }

    private Object invoke(String name, Class<?> prefixType, Object prefix) throws ReflectiveOperationException {
        // Android 14-16: attributionTag + userId + deviceId; Android 13:
        // attributionTag + userId; Android 10-12: userId; older: package only.
        Class<?>[][] signatures = {
            {String.class, String.class, int.class, int.class},
            {String.class, String.class, int.class},
            {String.class, int.class},
            {String.class}
        };
        Object[][] arguments = {
            {PACKAGE, null, 0, 0}, {PACKAGE, null, 0}, {PACKAGE, 0}, {PACKAGE}
        };
        for (int i = 0; i < signatures.length; i++) {
            Class<?>[] types = signatures[i];
            Object[] values = arguments[i];
            if (prefixType != null) {
                types = prepend(types, prefixType);
                values = prepend(values, prefix);
            }
            Method method;
            try {
                method = manager.getClass().getMethod(name, types);
            } catch (NoSuchMethodException missingSignature) {
                continue;
            }
            // Invocation failures must not fall through to a different signature.
            return method.invoke(manager, values);
        }
        throw new NoSuchMethodException("Unsupported clipboard signature: " + name);
    }

    private static <T> T[] prepend(T[] tail, T first) {
        T[] result = Arrays.copyOf(tail, tail.length + 1);
        System.arraycopy(tail, 0, result, 1, tail.length);
        result[0] = first;
        return result;
    }

    public CharSequence getText() {
        try {
            ClipData clip = (ClipData) invoke("getPrimaryClip", null, null);
            return clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        } catch (ReflectiveOperationException e) {
            Log.e("scrcpy", "Could not read clipboard", e);
            return null;
        }
    }

    public boolean setText(CharSequence text) {
        try {
            invoke("setPrimaryClip", ClipData.class, ClipData.newPlainText(null, text));
            return true;
        } catch (ReflectiveOperationException e) {
            Log.e("scrcpy", "Could not write clipboard", e);
            return false;
        }
    }

    public boolean addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
        return changeListener("addPrimaryClipChangedListener", listener);
    }

    /** Adapter for the 3.x public listener type used by Controller. */
    public void addPrimaryClipChangedListener(android.content.ClipboardManager.OnPrimaryClipChangedListener listener) {
        IOnPrimaryClipChangedListener bridge = (IOnPrimaryClipChangedListener) Proxy.newProxyInstance(
            IOnPrimaryClipChangedListener.class.getClassLoader(),
            new Class<?>[] { IOnPrimaryClipChangedListener.class },
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("asBinder".equals(method.getName())) {
                        return null;
                    }
                    listener.onPrimaryClipChanged();
                    return null;
                }
            });
        changeListener("addPrimaryClipChangedListener", bridge);
    }

    public boolean removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
        return changeListener("removePrimaryClipChangedListener", listener);
    }

    private boolean changeListener(String operation, IOnPrimaryClipChangedListener listener) {
        try {
            invoke(operation, IOnPrimaryClipChangedListener.class, listener);
            return true;
        } catch (ReflectiveOperationException e) {
            Log.e("scrcpy", "Could not update clipboard listener", e);
            return false;
        }
    }
}
