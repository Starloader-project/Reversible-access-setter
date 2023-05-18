package de.geolykt.starloader.ras;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central context for reversible access setters.
 *
 * @since 1.0.0
 */
public class ReversibleAccessSetterContext {

    static class RASAccessTransform {
        private static final int FAIL_HARD = 2;
        private static final int FAIL_SOFT = 0;
        private static final int FAIL_WARN = 1;

        private int failType;
        public final int originAccess;
        @NotNull
        private final List<String> sources = new CopyOnWriteArrayList<>();
        public final int targetAccess;
        public final int targetType;

        public RASAccessTransform(int originAccess, int targetAccess, int targetType, int failType, String source) {
            this.originAccess = originAccess;
            this.targetAccess = targetAccess;
            this.targetType = targetType;
            this.failType = failType;
            this.sources.add(source);
        }

        @Override
        public String toString() {
            return AccessUtil.stringifyAccess(this.originAccess, this.targetType) + " -> " + AccessUtil.stringifyAccess(this.targetAccess, this.targetType);
        }
    }

    private static class RASClassContext {
        @NotNull
        private final ConcurrentMap<Map.Entry<String, String>, List<@NotNull RASAccessTransform>> memberTransforms = new ConcurrentHashMap<>();
        @NotNull
        private final List<@NotNull RASAccessTransform> selfTransforms = new CopyOnWriteArrayList<>();

        public void accept(@NotNull ClassNode node) throws RASTransformFailure {
            for (RASAccessTransform transform : this.selfTransforms) {
                long result = AccessUtil.applyAccess(node.access, 0, transform);
                if ((result & ~AccessUtil.INT_LSB) == 0) {
                    node.access = (int) result;
                } else if (transform.failType == RASAccessTransform.FAIL_HARD) {
                    throw new RASTransformFailure("RAS transform \"" + transform + "\" from namespaces " + transform.sources + " failed for class \"" + node.name + "\": " + AccessUtil.getErrorCode(result));
                } else if (transform.failType == RASAccessTransform.FAIL_WARN) {
                    LOGGER.warn("ReversibleAccessSetter transform \"{}\" from namespaces {} failed to apply for class \"{}\": {}", transform, transform.sources, node.name, AccessUtil.getErrorCode(result));
                }
            }
            for (MethodNode method : node.methods) {
                List<@NotNull RASAccessTransform> transforms = memberTransforms.get(new SimpleImmutableEntry<>(method.name, method.desc));
                if (transforms == null) {
                    continue;
                }
                for (RASAccessTransform transform : transforms) {
                    long result = AccessUtil.applyAccess(method.access, 0, transform);
                    if ((result & ~AccessUtil.INT_LSB) == 0) {
                        method.access = (int) result;
                    } else if (transform.failType == RASAccessTransform.FAIL_HARD) {
                        throw new RASTransformFailure("RAS transform \"" + transform + "\" from namespaces " + transform.sources + " failed for method \"" + node.name + "." + method.name + method.desc + "\": " + AccessUtil.getErrorCode(result));
                    } else if (transform.failType == RASAccessTransform.FAIL_WARN) {
                        LOGGER.warn("ReversibleAccessSetter transform \"{}\" from namespaces {} failed to apply for method \"{}.{}{}\": {}", transform, transform.sources, node.name, method.name, method.desc, AccessUtil.getErrorCode(result));
                    }
                }
            }
            for (FieldNode field : node.fields) {
                List<@NotNull RASAccessTransform> transforms = memberTransforms.get(new SimpleImmutableEntry<>(field.name, field.desc));
                if (transforms == null) {
                    continue;
                }
                for (RASAccessTransform transform : transforms) {
                    long result = AccessUtil.applyAccess(field.access, 0, transform);
                    if ((result & ~AccessUtil.INT_LSB) == 0) {
                        field.access = (int) result;
                    } else if (transform.failType == RASAccessTransform.FAIL_HARD) {
                        throw new RASTransformFailure("RAS transform \"" + transform + "\" from namespaces " + transform.sources + " failed for field \"" + node.name + "." + field.name + ":" + field.desc + "\": " + AccessUtil.getErrorCode(result));
                    } else if (transform.failType == RASAccessTransform.FAIL_WARN) {
                        LOGGER.warn("ReversibleAccessSetter transform \"{}\" from namespaces {} failed to apply for field \"{}.{}:{}\": {}", transform, transform.sources, node.name, field.name, field.desc, AccessUtil.getErrorCode(result));
                    }
                }
            }
        }
    }

    /**
     * Exception that is thrown if a transform marked as failing hard (via the "!" special prefix)
     * could not occur.
     */
    public static class RASTransformFailure extends Exception {
        private static final long serialVersionUID = -2103204040204349662L;

        /**
         * Constructor.
         *
         * @param message The exception message
         */
        protected RASTransformFailure(String message) {
            super(message);
        }
    }

    /**
     * The scope of a transform - that is when or whether a transform is being applied.
     * The specification states:
     *
     * <p>There are times in which it does not make any sense to have a transform occur in certain
     * environments. These environments are defined through the so called "scopes".
     * The standard RAS specification defines following scopes:
     *
     * <ul>
     *   <li>a or all: The transform should always occur.</li>
     *   <li>b or build: The transform should only occur at build/compile-time as well as
     *   (if applicable) in the development environment.</li>
     *   <li>r or runtime: The transform should only occur at runtime.</li>
     * </ul>
     *
     * <p>Other dialects may define different scopes. This may be beneficial if a separation between
     * client and server is required. However, as galimulator does not make use of a server/client
     * system it makes little sense for the standard dialect to support clientside only and
     * serverside only transforms.
     *
     * @since 1.0.0
     */
    public enum RASTransformScope {
        /**
         * a or all: The transform should always occur
         *
         * @since 1.0.0
         */
        ALL,

        /**
         * b or build: The transform should only occur at build/compile-time as well as
         * (if applicable) in the development environment.
         *
         * @since 1.0.0
         */
        BUILDTIME,

        /**
         * r or runtime: The transform should only occur at runtime.
         *
         * @since 1.0.0
         */
        RUNTIME;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ReversibleAccessSetterContext.class);

    private static void addTransform(@NotNull List<@NotNull RASAccessTransform> transforms, int originAccess, int targetAccess, int targetType, int failType, String sourceNamespace) {
        for (RASAccessTransform transform : transforms) {
            if (transform.originAccess == originAccess
                    && transform.targetAccess == targetAccess
                    && transform.targetType == targetType) {
                if (transform.failType < failType) {
                    transform.failType = failType;
                }
                transform.sources.add(sourceNamespace);
                return;
            }
        }
        transforms.add(new RASAccessTransform(originAccess, targetAccess, targetType, failType, sourceNamespace));
    }

    @NotNull
    private final ConcurrentMap<String, RASClassContext> classes = new ConcurrentHashMap<>();
    private final boolean forcedSilence;
    @NotNull
    private final RASTransformScope scope;

    /**
     * Constructor for a new reversible access setter context.
     *
     * @param activeScope The scope to use for this context. Only transforms that are within this scope are applied,
     *                    all others are discarded. May not be {@link RASTransformScope#ALL}.
     * @param forcedSilence True to silence all transformation failures, even if the transform makes use of the
     *                      FAIL_HARD ('!') flag. That is all transformations are considered to have the FAIL_SOFT ('@')
     *                      flag.
     * @since 1.0.0
     */
    public ReversibleAccessSetterContext(@NotNull RASTransformScope activeScope, boolean forcedSilence) {
        if (activeScope == RASTransformScope.ALL) {
            throw new IllegalArgumentException("RASTransformScope may not be 'ALL'.");
        }
        this.scope = activeScope;
        this.forcedSilence = forcedSilence;
    }

    /**
     * Apply all relevant transforms known to this {@link ReversibleAccessSetterContext} to a given {@link ClassNode}.
     *
     * <p>Note: For performance's sake it is advisable to run {@link #isTarget(String)} before running this method.
     * The only exception is if the node is used for binding purposes (i.e. in a development environment) or for
     * decompilation, at which point this method <b>must</b> be run indiscriminately.
     *
     * @param node The {@link ClassNode} to apply transforms to.
     * @throws RASTransformFailure Exception thrown if a transform which has the FAIL_HARD flag set failed to apply.
     * @since 1.0.0
     */
    public void accept(@NotNull ClassNode node) throws RASTransformFailure {
        RASClassContext ctx = this.classes.get(node.name);
        if (ctx != null) {
            ctx.accept(node);
        }
        for (InnerClassNode icn : node.innerClasses) {
            RASClassContext innerCtx = this.classes.get(icn.name);
            if (innerCtx != null) {
                for (RASAccessTransform transform : innerCtx.selfTransforms) {
                    long result = AccessUtil.applyAccess(icn.access, 0, transform);
                    if ((result & ~AccessUtil.INT_LSB) == 0) {
                        icn.access = (int) result;
                    }
                }
            }
        }
    }

    /**
     * Check whether the given class is subject to one or more reversible access
     * transforms.
     *
     * <p>This method assumes that the class is not an inner class itself and that it is also not an outer class.
     * It also assumes that it isn't using an inner class is any other shape or form. This assumption is not of
     * relevance at runtime, but when classes that were applied with RAS transforms are being decompiled, the inner
     * accesses might get skewed. The same applies to the eclipse compiler, which trusts the inner class access more
     * than the actual class access.
     *
     * @param internalName The internal name of the class. It should look something like "org/example/Outer$Inner".
     * @return True if the class is subject to a transform, false.
     * @since 1.0.0
     */
    public boolean isTarget(@NotNull String internalName) {
        return this.classes.containsKey(internalName);
    }

    /**
     * Read a reversible access setter from a given {@link BufferedReader} as an input source.
     * The reversible access setter is put under a given namespace. Namespaces are an implementation-specific
     * feature that can be chosen at free will and is meant to aid the debugging of RAS transforms.
     * Outside of that, namespaces do not affect the flow of the application.
     *
     * @param namespace The identifying namespace of the transform.
     * @param reader The reader from which the reversible access setter is read from.
     * @param reversed True to reverse the RAS, false to apply it normally.
     * @throws IOException Exception that is thrown when the RAS is malformed or when the underlying
     * reader thrown an {@link IOException} itself.
     * @since 1.0.0
     */
    public void read(@NotNull String namespace, @NotNull BufferedReader reader, boolean reversed) throws IOException {
        String header = reader.readLine();
        int lineNumber = 1;
        while (header != null && (JavaInterop.isBlank(header) || header.codePointAt(0) == '#')) {
            header = reader.readLine();
            lineNumber++;
        }
        if (header == null) {
            throw new IOException("Input stream exhausted before reaching RAS header for namespace " + namespace + ".");
        }

        if (!header.startsWith("RAS")) {
            throw new IOException("Malformed ReversibleAccessSetter header of namespace " + namespace + ": Syntax error at line " + lineNumber + ": RAS header should begin with \"RAS\"");
        }

        String[] headerSplits = header.split("\\s+");
        if (headerSplits.length != 3) {
            throw new IOException("Malformed ReversibleAccessSetter of namespace " + namespace + ": Syntax error at line " + lineNumber + ": Expected format \"RAS <format-version> <format-dialect>\".");
        }

        String requestedFormatVersion = headerSplits[1];
        String requestedDialect = headerSplits[2];

        if (!(requestedFormatVersion.equals("v1") || requestedFormatVersion.equals("1")
                || requestedFormatVersion.equals("v1.0") || requestedFormatVersion.equals("1.1"))) {
            throw new IOException("ReversibleAccessSetter of namespace " + namespace + " has format version " + requestedFormatVersion + ", but this RAS implementation only supports one of ['1', 'v1', '1.1', 'v1.1'].");
        }

        if (!(requestedDialect.equals("std") || requestedDialect.equals("starrian"))) {
            throw new IOException("ReversibleAccessSetter of namespace " + namespace + " has format dialect " + requestedDialect + ", but this RAS implementation only supports one of ['std', 'starrian'].");
        }

        lineNumber++;
        for (String line = reader.readLine(); line != null; line = reader.readLine(), lineNumber++) {
            if (JavaInterop.isBlank(line) || line.codePointAt(0) == '#') {
                continue;
            }
            if (line.length() < 9) {
                // Guard against IOOBEs
                throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace \"" + namespace + "\": The smallest possible line length is 9 characters, but got " + line.length() + " chars instead.");
            }
            int prefix = line.codePointAt(0);
            int failType = RASAccessTransform.FAIL_WARN;
            if (Character.isWhitespace(prefix)) { // According to the spec only ' ' is allowed, but the impl is a bit more lenient there.
                // NOP
            } else if (prefix == '@') {
                failType = RASAccessTransform.FAIL_SOFT;
            } else if (prefix == '!') {
                failType = RASAccessTransform.FAIL_HARD;
            } else if (
                    (Character.isWhitespace(line.codePointAt(1)) && (prefix == 'a' || prefix == 'b' || prefix == 'r'))
                    || (Character.isWhitespace(line.codePointAt(3)) && line.startsWith("all"))
                    || (Character.isWhitespace(line.codePointAt(5)) && line.startsWith("build"))
                    || (Character.isWhitespace(line.codePointAt(7)) && line.startsWith("runtime"))) {
                // These conditions aren't allowed according to the spec, but the impl should be able to parse those cases anyways
                // since I expect this mistake to be done frequently.
                LOGGER.warn("Malformed ReversibleAccessSetter transform in line {} of namespace \"{}\": Special prefixes are not optional according to the spec. Consider resolving this issue to prevent failures in other RAS implementations.", lineNumber, namespace);
                line = ' ' + line;
                prefix = ' ';
            } else {
                throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace \"" + namespace + "\": Invalid prefix " + JavaInterop.codepointToString(prefix) + ".");
            }
            if (Character.isWhitespace(prefix)) {
                line = '0' + line.substring(1);
            }
            line = line.trim();
            String[] parts = line.split("\\s+");
            if (parts.length != 4 && parts.length != 6) {
                throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Expected format \"<prefix>scope <original-access> <target-access> <className>\" or \"<prefix>scope <original-access> <target-access> <className> <memberName> <memberDescriptor>\". (Consists of " + parts.length + " parts, but expected 4 or 6 parts)");
            }
            String scope = parts[0].substring(1);
            if (scope.isEmpty()) {
                throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Empty scope");
            }
            RASTransformScope rasScope;
            if (scope.equals("a") || scope.equals("all")) {
                rasScope = RASTransformScope.ALL;
            } else if (scope.equals("b") || scope.equals("build")) {
                rasScope = RASTransformScope.BUILDTIME;
            } else if (scope.equals("r") || scope.equals("runtime")) {
                rasScope = RASTransformScope.RUNTIME;
            } else {
                throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Unknown scope \"" + scope + "\". Make sure you use the right dialect!");
            }
            int leftAccess = AccessUtil.parseAccess(parts[1]);
            int rightAccess = AccessUtil.parseAccess(parts[2]);
            int leftAccessType = AccessUtil.getAccessCategory(parts[1]);
            int rightAccessType = AccessUtil.getAccessCategory(parts[2]);

            if (reversed) {
                int temp = leftAccess;
                leftAccess = rightAccess;
                rightAccess = temp;
                temp = leftAccessType;
                leftAccessType = rightAccessType;
                rightAccessType = temp;
            }

            if (leftAccess != 0 && rightAccess != 0) {
                boolean visibilityUnchanging = (leftAccess & AccessUtil.ANY_VISIBILITY_MODIFIER) == 0;
                if (visibilityUnchanging != ((rightAccess & AccessUtil.ANY_VISIBILITY_MODIFIER) == 0)) {
                    throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Incompatible accesses.");
                } else if (visibilityUnchanging && leftAccess != rightAccess) {
                    throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Incompatible accesses.");
                }
            }

            if (leftAccessType == AccessUtil.TARGET_MODULE || rightAccessType == AccessUtil.TARGET_MODULE) {
                throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access can only be applied on module-info.class entries, which cannot be changed by RAS as of v1.0.");
            }

            if (rasScope != RASTransformScope.ALL && rasScope != this.scope) {
                continue;
            }

            if (this.forcedSilence) {
                failType = RASAccessTransform.FAIL_SOFT;
            }

            String className = parts[3];
            RASClassContext context = this.classes.get(className);
            if (context == null) {
                context = new RASClassContext();
                RASClassContext var10001 = this.classes.putIfAbsent(className, context);
                if (var10001 != null) {
                    context = var10001;
                }
            }
            if (parts.length == 4) {
                // Class mapping
                if ((leftAccessType != AccessUtil.TARGET_ANY && leftAccessType != AccessUtil.TARGET_CLASS)
                        || (rightAccessType != AccessUtil.TARGET_ANY && rightAccessType != AccessUtil.TARGET_CLASS)) {
                    throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access cannot be applied on classes.");
                }
                addTransform(context.selfTransforms, leftAccess, rightAccess, AccessUtil.andAccessTypes(leftAccessType, rightAccessType), failType, namespace);
            } else {
                String memberName = parts[4];
                String memberDesc = parts[5];
                if (memberDesc.codePointAt(0) == '(') {
                    // Method
                    if ((leftAccessType != AccessUtil.TARGET_ANY && leftAccessType != AccessUtil.TARGET_METHOD)
                            || (rightAccessType != AccessUtil.TARGET_ANY && rightAccessType != AccessUtil.TARGET_METHOD)) {
                        throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access cannot be applied on methods.");
                    }
                } else {
                    // Field
                    if ((leftAccessType != AccessUtil.TARGET_ANY && leftAccessType != AccessUtil.TARGET_FIELD)
                            || (rightAccessType != AccessUtil.TARGET_ANY && rightAccessType != AccessUtil.TARGET_FIELD)) {
                        throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access cannot be applied on fields.");
                    }
                }
                Map.Entry<String, String> entry = new SimpleImmutableEntry<>(memberName, memberDesc);
                List<RASAccessTransform> transforms = context.memberTransforms.get(entry);
                if (transforms == null) {
                    transforms = new CopyOnWriteArrayList<>();
                    List<RASAccessTransform> var10001 = context.memberTransforms.putIfAbsent(entry, transforms);
                    if (var10001 != null) {
                        transforms = var10001;
                    }
                }
                addTransform(transforms, leftAccess, rightAccess, rightAccessType, failType, namespace);
            }
        }
    }
}
