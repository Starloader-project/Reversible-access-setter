package de.geolykt.starloader.ras;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.api.TrRemapper;

/**
 * An implementation of {@link OutputConsumerPath.ResourceRemapper} that remaps any reversible
 * access setter files. However, the reversible access setter itself is not applied to the
 * classes that need to be remapped.
 *
 *  <p>This class does not define {@link OutputConsumerPath.ResourceRemapper#canTransform(TinyRemapper, Path)}
 *  and thus in itself does not provide any logic to know whether a file is a reversible access transformer.
 *  This decision must be performed by subclasses.
 *
 * @since 1.0.0
 */
public abstract class AbstractTinyRASRemapper implements OutputConsumerPath.ResourceRemapper {

    /**
     * Obtain the {@link Logger} instance that should be used to log not completely fatal
     * warning messages. This includes instances where the access setter file is malformed,
     * but the line is being skipped in order to enable a minimum amount of usability.
     *
     * @return The used {@link Logger} instance
     * @since 1.0.0
     */
    protected Logger getLogger() {
        return LoggerFactory.getLogger(AbstractTinyRASRemapper.class);
    }

    /**
     * Remap a RAS file, reading the source from a given {@link BufferedReader} and writing the remapped contents
     * to the given {@link BufferedWriter}.
     * @param reader The {@link BufferedReader} instance to read the RAS from
     * @param bw The {@link BufferedWriter} instance to write the remapped RAS file to
     * @param namespace The namespace name of the RAS file to use for logging slightly malformed lines.
     * @param tinyRemapper The remapper instance to use to remap classes and class member references.
     * @throws IOException Exception that is thrown if the underlying reader or writer threw an {@link IOException}
     * or if the read RAS file has a malformed header.
     * @since 1.0.0
     */
    protected void remapRAS(@NotNull BufferedReader reader, BufferedWriter bw, String namespace, TinyRemapper tinyRemapper) throws IOException {
        TrRemapper remapper = tinyRemapper.getEnvironment().getRemapper();
        String header = reader.readLine();
        int lineNumber = 1;
        while (header != null && (JavaInterop.isBlank(header) || header.codePointAt(0) == '#')) {
            bw.write(header);
            bw.newLine();
            header = reader.readLine();
            lineNumber++;
        }
        if (header == null) {
            throw new IOException("Input stream exhausted before reaching RAS header for namespace " + namespace + ".");
        }
        bw.write(header);
        bw.newLine();

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
                bw.write(line);
                bw.newLine();
                continue;
            }
            if (line.length() < 9) {
                // Guard against IOOBEs
                getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace \"" + namespace + "\": The smallest possible line length is 9 characters, but got " + line.length() + " chars instead.");
                continue;
            }
            int prefix = line.codePointAt(0);
            if (Character.isWhitespace(prefix) // According to the spec only ' ' is allowed, but the impl is a bit more lenient there.
                || prefix == '@' || prefix == '!') {
                    // NOP
            } else if (
                    (Character.isWhitespace(line.codePointAt(1)) && (prefix == 'a' || prefix == 'b' || prefix == 'r'))
                    || (Character.isWhitespace(line.codePointAt(3)) && line.startsWith("all"))
                    || (Character.isWhitespace(line.codePointAt(5)) && line.startsWith("build"))
                    || (Character.isWhitespace(line.codePointAt(7)) && line.startsWith("runtime"))) {
                // These conditions aren't allowed according to the spec, but the impl should be able to parse those cases anyways
                // since I expect this mistake to be done frequently.
                getLogger().warn("Malformed ReversibleAccessSetter transform in line {} of namespace \"{}\": Special prefixes are not optional according to the spec. Consider resolving this issue to prevent failures in other RAS implementations.", lineNumber, namespace);
                line = ' ' + line;
                prefix = ' ';
            } else {
                getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace \"" + namespace + "\": Invalid prefix " + JavaInterop.codepointToString(prefix) + ".");
                continue;
            }
            if (Character.isWhitespace(prefix)) {
                line = '0' + line.substring(1);
            }
            line = line.trim();
            String[] parts = line.split("\\s+");
            if (parts.length != 4 && parts.length != 6) {
                getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Expected format \"<prefix>scope <original-access> <target-access> <className>\" or \"<prefix>scope <original-access> <target-access> <className> <memberName> <memberDescriptor>\". (Consists of " + parts.length + " parts, but expected 4 or 6 parts)");
                continue;
            }
            String scope = parts[0].substring(1);
            if (scope.isEmpty()) {
                getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Empty scope");
                continue;
            }
            if (!scope.equals("a") && !scope.equals("all")
                    && !scope.equals("b") && !scope.equals("build")
                    && !scope.equals("r") && !scope.equals("runtime")) {
                getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Unknown scope \"" + scope + "\". Make sure you use the right dialect!");
                continue;
            }
            int leftAccess = AccessUtil.parseAccess(parts[1]);
            int rightAccess = AccessUtil.parseAccess(parts[2]);
            int leftAccessType = AccessUtil.getAccessCategory(parts[1]);
            int rightAccessType = AccessUtil.getAccessCategory(parts[2]);

            if (leftAccess != 0 && rightAccess != 0) {
                boolean visibilityUnchanging = (leftAccess & AccessUtil.ANY_VISIBILITY_MODIFIER) == 0;
                if (visibilityUnchanging != ((rightAccess & AccessUtil.ANY_VISIBILITY_MODIFIER) == 0)) {
                    throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Incompatible accesses.");
                } else if (visibilityUnchanging && leftAccess != rightAccess) {
                    throw new IOException("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": Incompatible accesses.");
                }
            }

            if (leftAccessType == AccessUtil.TARGET_MODULE || rightAccessType == AccessUtil.TARGET_MODULE) {
                getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access can only be applied on module-info.class entries, which cannot be changed by RAS as of v1.0.");
                continue;
            }

            String className = remapper.map(parts[3]);
            if (parts.length == 4) {
                // Class mapping
                if ((leftAccessType != AccessUtil.TARGET_ANY && leftAccessType != AccessUtil.TARGET_CLASS)
                        || (rightAccessType != AccessUtil.TARGET_ANY && rightAccessType != AccessUtil.TARGET_CLASS)) {
                    getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access cannot be applied on classes.");
                    continue;
                }
                line = JavaInterop.codepointToString(prefix) + scope + " " + parts[1] + " " + parts[2] + " " + className;
            } else {
                String memberName = parts[4];
                String memberDesc = parts[5];
                if (memberDesc.codePointAt(0) == '(') {
                    // Method
                    if ((leftAccessType != AccessUtil.TARGET_ANY && leftAccessType != AccessUtil.TARGET_METHOD)
                            || (rightAccessType != AccessUtil.TARGET_ANY && rightAccessType != AccessUtil.TARGET_METHOD)) {
                        getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access cannot be applied on methods.");
                        continue;
                    }
                    memberName = remapper.mapMethodName(parts[3], memberName, memberDesc);
                    memberDesc = remapper.mapMethodDesc(memberDesc);
                } else {
                    // Field
                    if ((leftAccessType != AccessUtil.TARGET_ANY && leftAccessType != AccessUtil.TARGET_FIELD)
                            || (rightAccessType != AccessUtil.TARGET_ANY && rightAccessType != AccessUtil.TARGET_FIELD)) {
                        getLogger().error("Malformed ReversibleAccessSetter transform in line " + lineNumber + " of namespace " + namespace + ": This access cannot be applied on fields.");
                        continue;
                    }
                    memberName = remapper.mapFieldName(parts[3], memberName, memberDesc);
                    memberDesc = remapper.mapDesc(memberDesc);
                }
                line = JavaInterop.codepointToString(prefix) + scope + " " + parts[1] + " " + parts[2] + " " + className + " " + memberName + " " + memberDesc;
            }

            bw.write(line);
            bw.newLine();
        }
    }

    @Override
    public void transform(Path destinationDirectory, Path relativePath, InputStream input, TinyRemapper remapper)
            throws IOException {
        if (relativePath == null) {
            throw new NullPointerException("relativePath was null");
        }
        Path outputFile = destinationDirectory.resolve(relativePath);
        Path outputDir = outputFile.getParent();
        if (outputDir != null) {
            Files.createDirectories(outputDir);
        }

        try (BufferedWriter out = Files.newBufferedWriter(outputFile)) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(input))) {
                remapRAS(in, out, relativePath.toString(), remapper);
            }
        }
    }
}
