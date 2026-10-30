package com.radiance.extra.compatibility.veil;

import java.util.IdentityHashMap;
import java.util.Map;

/** Compiled objects own CPU layouts; numeric GPU IDs may alias across pipeline generations. */
final class VeilProgramModels<T> {
    private final Map<Object, T> entries = new IdentityHashMap<>();

    synchronized void put(Object owner, T model) {
        entries.put(owner, model);
    }

    synchronized T get(Object owner) {
        return entries.get(owner);
    }

    synchronized void release(Object owner) {
        entries.remove(owner);
    }
}
