/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 通过反射连接 gomobile 生成的
 * {@code dev.lan.atticusvpn.atticusengine.Atticusengine}。
 *
 * <p>这样源码和 JVM 测试不依赖本地生成物；发布构建仍必须把 AAR 放入 app/libs。
 * 反射兼容 gomobile 把 Go {@code int} 映射为 Java int 或 long 的两种生成结果。</p>
 */
public final class ReflectiveNativeEngine implements NativeEngine {
    private static final String CLASS_NAME =
            "dev.lan.atticusvpn.atticusengine.Atticusengine";

    private final Class<?> bridge;

    public ReflectiveNativeEngine() throws NativeEngineException {
        try {
            bridge = Class.forName(CLASS_NAME);
        } catch (final ReflectiveOperationException | LinkageError error) {
            throw new NativeEngineException("NATIVE_ENGINE_UNAVAILABLE");
        }
    }

    @Override
    public String version() throws NativeEngineException {
        return requireString(invoke("version"));
    }

    @Override
    public void setNetworkHandle(final long networkHandle) throws NativeEngineException {
        invoke("setNetworkHandle", networkHandle);
    }

    @Override
    public void start(
            final String serverUapi,
            final String upstreamDnsCsv,
            final int mtu) throws NativeEngineException {
        invoke("start", serverUapi, upstreamDnsCsv, mtu);
    }

    @Override
    public int socketFd4() throws NativeEngineException {
        return requireInt(invokeEither("socketFD4", "socketFd4"));
    }

    @Override
    public int socketFd6() throws NativeEngineException {
        return requireInt(invokeEither("socketFD6", "socketFd6"));
    }

    @Override
    public void stop() throws NativeEngineException {
        invoke("stop");
    }

    @Override
    public String status() throws NativeEngineException {
        return requireString(invoke("status"));
    }

    private Object invokeEither(final String first, final String second)
            throws NativeEngineException {
        try {
            return invoke(first);
        } catch (final NativeEngineException ignored) {
            return invoke(second);
        }
    }

    private Object invoke(final String name, final Object... arguments)
            throws NativeEngineException {
        final Method method = findMethod(name, arguments.length);
        final Object[] converted = convertArguments(method.getParameterTypes(), arguments);
        try {
            return method.invoke(null, converted);
        } catch (final IllegalAccessException | InvocationTargetException | RuntimeException error) {
            // gomobile 的 error 文本只允许在 native 内部处理；Java UI 仅接收固定码。
            throw new NativeEngineException("NATIVE_ENGINE_CALL_FAILED");
        }
    }

    private Method findMethod(final String name, final int parameterCount)
            throws NativeEngineException {
        for (final Method method : bridge.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    && method.getName().equals(name)
                    && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NativeEngineException("NATIVE_ENGINE_API_MISMATCH");
    }

    private static Object[] convertArguments(final Class<?>[] types, final Object[] values)
            throws NativeEngineException {
        final Object[] converted = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            final Object value = values[index];
            final Class<?> type = types[index];
            if (value instanceof Number number && (type.isPrimitive() || Number.class.isAssignableFrom(type))) {
                if (type == long.class || type == Long.class) {
                    converted[index] = number.longValue();
                } else if (type == int.class || type == Integer.class) {
                    converted[index] = number.intValue();
                } else {
                    throw new NativeEngineException("NATIVE_ENGINE_API_MISMATCH");
                }
            } else if (type.isInstance(value)) {
                converted[index] = value;
            } else {
                throw new NativeEngineException("NATIVE_ENGINE_API_MISMATCH");
            }
        }
        return converted;
    }

    private static int requireInt(final Object value) throws NativeEngineException {
        if (!(value instanceof Number number)) {
            throw new NativeEngineException("NATIVE_ENGINE_API_MISMATCH");
        }
        return number.intValue();
    }

    private static String requireString(final Object value) throws NativeEngineException {
        if (!(value instanceof String text)) {
            throw new NativeEngineException("NATIVE_ENGINE_API_MISMATCH");
        }
        return text;
    }
}
