package de.geolykt.starloader.ras;

import org.jetbrains.annotations.NotNull;

final class JavaInterop {
    public static final boolean isBlank(@NotNull String string) {
        return string.isBlank();
    }

    @NotNull
    public static final String codepointToString(int codepoint) {
        return Character.toString(codepoint);
    }
}
