# Reversible access setters ("RAS" / ".ras"-files)

Document version 1.0

## Motivation

The reversible access setters (abbreviated as "RAS" later on) format expands on the
commonly used access transformers as employed by MinecraftForge and the access widener
format employed by FabricMC. Unlike the AT or AW formats, the RAS format is designed to
be inheriently reversible, that is it is possible to regenerate the original class file
only based on the transformed class file and the RAS transformer file.
Unlike AW and to a lesser extend AT, the RAS format is not designed to be a fool-proof
approach to access transformation in java and thus knowingly allows unsafe or nonsensical
transformations, though implementations may verify the sanity of flags (e.g. classes
shouldn't have ACC\_PRIVATE).

## Friendly warnings

**RAS is powerful. You'll be in a world of hurt if you use it unwisely.** If possible,
Mixins should be used instead.

## Header format

The first line of any .ras file is the header. It follows the following format:

```
RAS <format-version> <format-dialect>
```

Unlike AW, the RAS format does not specify which mappings should be used. Ideally,
this is handled by the remapper but if all other approaches fail the format-dialect
field *can* be used to specify the used deobfuscation mappings.

- Format-version: Any of `1`, `v1`, `1.0` or `v1.0`.
- Format-dialect: The dialect of the format to use. Each dialect may define different
scopes or give a different meaning to them. The dialect that corresponds to this
document is `std` or `starrian`.

**NOTE:** The header can be preceeded by blank lines and comment lines (that is lines
immediately starting with a `#`).

## Transform format

All other non-blank lines are so called "transforms" (unless a comment prefix is used,
see "Special prefixes"). They have following formats (one format per line):

```
<prefix>scope <original-access> <target-access> <className>
<prefix>scope <original-access> <target-access> <className> <memberName> <memberDescriptor>
```

Note: The prefix is optional

`<original-access>` and `<target-access>` are one of following values (note: they should be
  case-insensitive):
  - `0`: Negate relevant modifier
  - `ACC_ABSTRACT` or `abstract`: Your only use will be to add it or remove it from classes - for
  whatever reason you need that. Technically it is legal to add or remove it from methods but
  uh that won't work well as-is.
  - `ACC_ENUM` or `enum`: **WARNING:** Removing ACC_ENUM will cause runtime errors if enum constants
  are accessed reflectively.
  - `ACC_SYNCHRONIZED` or `synchronized`
  - `ACC_PRIVATE` or `private`
  - `ACC_PROTECTED` or `protected`: **WARNING:** Adding or removing ACC_PROTECTED may cause
  issues with method overloading, a potentially hard-to-debug mistake.
  - `ACC_PUBLIC` or `public`: **WARNING:** Adding or removing ACC_PUBLIC may cause
  issues with method overloading, a potentially hard-to-debug mistake. 
  - `ACC_TRANSIENT` or `transient`: **WARNING:** Adding or removing ACC_TRANSIENT may cause
  issues with serialisation, which can be difficult to spot.
  - `ACC_FINAL` or `final`
  - `ACC_INTERFACE` or `interface`: **WARNING:** Converting a class to a interface or vice-versa
  is beyond stupid. And won't work in most cases due to the constructor. Only use in conjunction
  with other class file transformers.
  - `ACC_STATIC` or `static`: **WARNING:** Adding or removing ACC_STATIC from methods will cause
  issues with LVT and may cause issues with method overloading.
  - `ACC_STRICT` or `strictfp`: **WARNING:** Strictfp does nothing in newer JVM versions and may
  get removed from the JVMS in the future.
  - `ACC_VOLATILE` or `volatile`
  - `ACC_NATIVE` or `native`: **WARNING:** Remvoing ACC_NATIVE will do nothing good. Adding it
  is probably stupid too.
  - `ACC_SUPER` or `super`: **WARNING:** ACC_SUPER does nothing and may get removed from the
  JVMS in the future.
  - `ACC_ANNOTATION` or `annotation`: **WARNING:** Adding or removing ACC_ANNOTATION is never
  approrpiate, but exists for the sake of completion.
  - `ACC_DEPRECATED`, or `deprecated`
  - `ACC_RECORD` or `record`: If you need to do some serious mental gymnastics
  - `ACC_SYNTHETIC` or `synthetic`
  - `ACC_VARARGS` or `varargs`

Examples:

```
# Remove ACC_ENUM on class "com/example/Enum", but only at compile-time.
 b enum 0 com/example/Enum
# Add ACC_SYNCHRONIZED on com/example/Enum.myMethod()V
 a 0 synchronized com/example/Enum myMethod ()V

# Make a private method public
 a private public com/example/Enum myMethod ()V
```

## Scopes

There are times in which it does not make any sense to have a transform occur in certain
environments. These environments are defined through the so called "scopes".
The standard RAS specification defines following scopes:

 - `a` or `all`: The transform should always occur.
 - `b` or `build`: The transform should only occur at build/compile-time as well as
 (if applicable) in the development environment.
 - `r` or `runtime`: The transform should only occur at runtime.

Other dialects may define different scopes. This may be beneficial if a separation between
client and server is required. However, as galimulator does not make use of a server/client
system it makes little sense for the standard dialect to support clientside only and
serverside only transforms.

Note: To adhere to future changes in the RAS format, scopes defined by dialects
should only include following characters: `[a-zA-Z]`.

## Special prefixes

Sometimes, the first character of a line has a special meaning. Following characters
have a special meaning:

 - `#`: This character marks a comment
 - ` `: Special escape character (if you want to keep a tidy table-like structure)
 - `@`: Any warnings for this transform are to be suppressed (by default the transformer
 should warn if it is unable to apply the transform)
 - `!`: Throw error if the transform cannot take place. What exactly this means is up
 to the implementation, it can range anything from a build failure to classloading failures
 or even the application not starting up.

## Whitespaces

The RAS format expects that class names and members names and their descriptors do not
contain any whitespace characters and thus does not support special character escapes.

All trailing whitespace of each line has to be trimmed.
Consecutive whitespace characters are also interpreted as if they were a single character.

Whitespace characters are valid as per [Character#isWhitespace](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html#isWhitespace(int)),
with the expection of newlines( `\n`), which is instead considered a seperator for new lines.

Any leading carriage returns (`\r`) have to be stripped.
