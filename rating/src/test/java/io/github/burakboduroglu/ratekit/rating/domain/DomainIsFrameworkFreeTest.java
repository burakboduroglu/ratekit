package io.github.burakboduroglu.ratekit.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The domain package must stay plain Java: no Spring, no persistence, no messaging imports. */
class DomainIsFrameworkFreeTest {

    private static final Path DOMAIN = Path.of("src/main/java/io/github/burakboduroglu/ratekit/rating/domain");
    private static final List<String> FORBIDDEN =
            List.of("org.springframework", "jakarta.", "javax.", "org.apache.kafka", "java.sql", "com.fasterxml");

    @Test
    void noFrameworkImportsInTheDomainPackage() throws IOException {
        try (Stream<Path> files = Files.list(DOMAIN)) {
            List<Path> sources = files.filter(f -> f.toString().endsWith(".java")).toList();
            assertThat(sources).as("domain sources were found").isNotEmpty();
            for (Path source : sources) {
                List<String> imports = Files.readAllLines(source).stream()
                        .filter(line -> line.startsWith("import "))
                        .toList();
                assertThat(imports)
                        .as("imports of %s", source.getFileName())
                        .noneMatch(line -> FORBIDDEN.stream().anyMatch(line::contains));
            }
        }
    }
}
