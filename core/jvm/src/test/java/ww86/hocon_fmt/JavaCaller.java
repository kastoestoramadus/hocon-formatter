package ww86.hocon_fmt;

import java.util.Optional;

/**
 * Calls the facade the way the Gradle and Maven plugins do. That this compiles is part of the
 * test: it needs a static entry point and a checked exception javac can see.
 */
final class JavaCaller {

  static String describe(byte[] content) {
    try {
      Optional<String> formatted = JvmFacade.reformat(content);
      return formatted.map(text -> "reformat to: " + text).orElse("already formatted");
    } catch (FormatRefusedException e) {
      return "refused: " + e.getMessage();
    }
  }
}
