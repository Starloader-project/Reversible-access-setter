package de.geolykt.starloader.ras;

import org.jetbrains.annotations.NotNull;

final class JavaInterop {
    public static final boolean isBlank(@NotNull String string) {
        int length = string.length();
        for (int i = 0; i < length; i++) {
            if (!Character.isWhitespace(string.codePointAt(i))) {
                return false;
            }
        }
        return true;
    }

    @NotNull
    public static final String codepointToString(int codepoint) {
        return new String(new int[] {codepoint}, 0, 1);
    }
}
