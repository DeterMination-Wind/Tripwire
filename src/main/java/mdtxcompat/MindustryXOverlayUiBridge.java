package mdtxcompat;

import arc.func.Prov;
import arc.scene.Element;
import arc.scene.ui.layout.Table;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class MindustryXOverlayUiBridge implements OverlayUiBridge {
    private final Object overlayUi;
    private final Method registerWindow;
    private final Method getOpen;
    private final Method toggle;

    public MindustryXOverlayUiBridge() {
        Object instance = null;
        Method register = null;
        Method open = null;
        Method toggleMethod = null;
        try {
            Class<?> overlayClass = Class.forName("mindustryX.features.ui.OverlayUI");
            instance = overlayClass.getField("INSTANCE").get(null);
            register = overlayClass.getMethod("registerWindow", String.class, Table.class);
            open = overlayClass.getMethod("getOpen");
            toggleMethod = overlayClass.getMethod("toggle");
        } catch (ReflectiveOperationException ignored) {
        }
        overlayUi = instance;
        registerWindow = register;
        getOpen = open;
        toggle = toggleMethod;
    }

    @Override
    public boolean isSupported() {
        return overlayUi != null;
    }

    @Override
    public OverlayWindowHandle registerWindow(String name, Table table, Prov<Boolean> availability) {
        if (!isSupported()) return OverlayUiBridge.UNSUPPORTED.registerWindow(name, table, availability);
        Object window = invoke(registerWindow, overlayUi, name, table);
        if (availability != null) {
            invoke(method(window, "setAvailability", Prov.class), window, availability);
        }
        return new WindowHandle(window);
    }

    @Override
    public void closeEditorIfOpen() {
        if (!isSupported()) return;
        Object open = invoke(getOpen, overlayUi);
        if (Boolean.TRUE.equals(open)) {
            invoke(toggle, overlayUi);
        }
    }

    private static Method method(Object target, String name, Class<?>... parameters) {
        try {
            return target.getClass().getMethod(name, parameters);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MindustryX OverlayUI method missing: " + name, e);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access MindustryX OverlayUI method: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException)cause;
            if (cause instanceof Error) throw (Error)cause;
            throw new IllegalStateException("MindustryX OverlayUI method failed: " + method.getName(), cause);
        }
    }

    private static class WindowHandle implements OverlayWindowHandle {
        private final Object window;

        private WindowHandle(Object window) {
            this.window = window;
        }

        @Override
        public void configure(boolean autoHeight, boolean resizable) {
            invoke(method(window, "setAutoHeight", boolean.class), window, autoHeight);
            invoke(method(window, "setResizable", boolean.class), window, resizable);
        }

        @Override
        public void setEnabledAndPinned(boolean enabled, boolean pinned) {
            Object data = invoke(method(window, "getData"), window);
            invoke(method(data, "setEnabled", boolean.class), data, enabled);
            invoke(method(data, "setPinned", boolean.class), data, pinned);
        }

        @Override
        public Boolean getEnabled() {
            Object data = invoke(method(window, "getData"), window);
            return (Boolean)invoke(method(data, "getEnabled"), data);
        }

        @Override
        public Element asElement() {
            return window instanceof Element ? (Element)window : null;
        }
    }
}
