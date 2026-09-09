package com.angryguyy.metadatastripper.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves private runtime fields with explicit names and validated type fallbacks.
 * <p>
 * This utility is critical for maintaining compatibility across minor Minecraft and Paper/Folia
 * version updates (e.g., 1.21.1 vs 1.21.3) where NMS (Net.Minecraft.Server) obfuscation mappings
 * might change field names but retain unique type signatures. By encapsulating reflection logic here,
 * version-sensitive failures remain localized, predictable, and observable during startup.
 * <p>
 * <b>Architectural Notes:</b>
 * <ul>
 *   <li><b>Execution Context:</b> These methods perform expensive class hierarchy traversals and array
 *       allocations. They are designed to be executed <b>strictly during plugin initialization</b>
 *       (e.g., inside static blocks or constructors like in {@link com.angryguyy.metadatastripper.listeners.NettyInjector}).
 *       They must <i>never</i> be called dynamically inside the Netty packet outbound hot-path.</li>
 *   <li><b>Security & Access:</b> Automatically forces {@code field.setAccessible(true)} on resolved
 *       fields, bypassing Java's standard access control checks to access deep NMS internals.</li>
 * </ul>
 */
public final class ReflectionAccess {

    private ReflectionAccess() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Finds a field by its explicit name across a class hierarchy and strictly verifies its expected type.
     * <p>
     * The search begins at the provided {@code owner} class and climbs up the superclass chain
     * (stopping at {@link Object}) until the field is found. If the field is found but its type
     * does not match the expected type, it immediately throws an exception rather than continuing the search.
     *
     * @param owner the starting class to inspect (typically an NMS packet or chunk class)
     * @param name  the expected obfuscated or mapped name of the field
     * @param type  the expected {@link Class} type of the field
     * @return the resolved, accessible {@link Field} instance
     * @throws NoSuchFieldException if the field does not exist anywhere in the hierarchy,
     *                              or if it exists but its type violates the expected signature
     */
    public static Field findField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Class<?> current = owner;

        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);

                if (!type.isAssignableFrom(field.getType()) && field.getType() != type) {
                    throw new NoSuchFieldException("Unexpected type for " + current.getName() + "." + name);
                }

                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException exception) {
                current = current.getSuperclass();
            }
        }

        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    /**
     * Finds a uniquely typed field across a class hierarchy when a stable name is unavailable.
     * <p>
     * <b>Obfuscation Fallback Strategy:</b> If Mojang's mappings change the name of a field (e.g., from {@code a} to {@code b}),
     * but the field is the <i>only</i> field of its specific type (e.g., a specific NMS payload object)
     * within the class hierarchy, this method will successfully resolve it.
     * <p>
     * The method scans the entire hierarchy and guarantees safety by throwing an exception if ambiguity
     * is detected (i.e., if zero or multiple fields share the targeted type).
     *
     * @param owner the starting class to inspect
     * @param type  the expected, uniquely identifiable {@link Class} type of the target field
     * @return the resolved, accessible {@link Field} instance
     * @throws NoSuchFieldException if no field of the expected type is found, or if <b>more than one</b> candidate exists,
     *                              making a safe resolution impossible
     */
    public static Field findUniqueField(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        List<Field> candidates = new ArrayList<>();
        Class<?> current = owner;

        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == type) {
                    candidates.add(field);
                }
            }
            current = current.getSuperclass();
        }

        if (candidates.size() != 1) {
            throw new NoSuchFieldException("Expected exactly one " + type.getName() + " field in " + owner.getName()
                    + " but found " + candidates.size());
        }

        Field field = candidates.get(0);
        field.setAccessible(true);
        return field;
    }
}