package poc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final Path MAIN_SRC = Path.of("src/main/java");
  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
  private static final Pattern IMPORT_PATTERN =
      Pattern.compile("^\\s*import\\s+(?:static\\s+)?([a-zA-Z0-9_.*]+)\\s*;", Pattern.MULTILINE);

  record SourceUnit(
      Path file, String packageName, String className, Set<String> imports, Class<?> loadedClass) {
    String fqcn() {
      return packageName + "." + className;
    }

    boolean isInLayer(String layer) {
      return packageName.endsWith("." + layer) || packageName.contains("." + layer + ".");
    }

    boolean isInFeature(String feature) {
      return packageName.equals("poc." + feature)
          || packageName.startsWith("poc." + feature + ".");
    }
  }

  private static List<SourceUnit> units;

  @BeforeAll
  static void scanSources() throws Exception {
    units = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        String content = Files.readString(file);
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(content);
        assertThat(pkgMatcher.find()).as("Package declaration in %s", file).isTrue();
        String pkg = pkgMatcher.group(1);

        String fileName = file.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - ".java".length());

        Set<String> imports = new LinkedHashSet<>();
        Matcher impMatcher = IMPORT_PATTERN.matcher(content);
        while (impMatcher.find()) {
          imports.add(impMatcher.group(1));
        }

        Class<?> clazz = Class.forName(pkg + "." + simpleName);
        for (Class<?> referenced : collectReferencedClasses(clazz)) {
          if (referenced.getName().startsWith("poc.")
              || referenced.getName().startsWith("org.flowable.")
              || referenced.getName().startsWith("org.springframework.")
              || referenced.getName().startsWith("jakarta.persistence.")) {
            imports.add(referenced.getName());
          }
        }
        units.add(new SourceUnit(file, pkg, simpleName, imports, clazz));
      }
    }
    assertThat(units).isNotEmpty();
  }

  private static Set<Class<?>> collectReferencedClasses(Class<?> clazz) {
    Set<Class<?>> refs = new LinkedHashSet<>();
    addType(refs, clazz.getSuperclass());
    for (Class<?> iface : clazz.getInterfaces()) {
      addType(refs, iface);
    }
    for (Annotation ann : clazz.getDeclaredAnnotations()) {
      addType(refs, ann.annotationType());
    }
    for (Field field : clazz.getDeclaredFields()) {
      addType(refs, field.getType());
      for (Annotation ann : field.getDeclaredAnnotations()) {
        addType(refs, ann.annotationType());
      }
    }
    for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
      for (Class<?> param : ctor.getParameterTypes()) {
        addType(refs, param);
      }
      for (Annotation ann : ctor.getDeclaredAnnotations()) {
        addType(refs, ann.annotationType());
      }
    }
    for (Method method : clazz.getDeclaredMethods()) {
      addType(refs, method.getReturnType());
      for (Class<?> param : method.getParameterTypes()) {
        addType(refs, param);
      }
      for (Annotation ann : method.getDeclaredAnnotations()) {
        addType(refs, ann.annotationType());
      }
    }
    return refs;
  }

  private static void addType(Set<Class<?>> target, Class<?> type) {
    if (type == null) {
      return;
    }
    if (type.isArray()) {
      addType(target, type.getComponentType());
      return;
    }
    if (!type.isPrimitive()) {
      target.add(type);
    }
  }

  @Test
  void packageByFeatureAndHexagonalLayerStructureIsEnforced() {
    for (SourceUnit unit : units) {
      if (unit.fqcn().equals("poc.Application")) {
        continue;
      }
      assertThat(
              unit.isInFeature("validation")
                  || unit.isInFeature("executionplan")
                  || unit.isInFeature("validationprocess"))
          .as(
              "Class %s must reside in a defined feature package (validation, executionplan, validationprocess)",
              unit.fqcn())
          .isTrue();

      assertThat(
              unit.isInLayer("domain")
                  || unit.isInLayer("application")
                  || unit.isInLayer("infrastructure")
                  || unit.isInLayer("api"))
          .as(
              "Class %s must reside in a hexagonal layer (domain, application, infrastructure, api)",
              unit.fqcn())
          .isTrue();
    }
  }

  @Test
  void domainLayerMustBePureAndFreeOfFrameworksAndOuterLayers() {
    List<SourceUnit> domainUnits = units.stream().filter(u -> u.isInLayer("domain")).toList();
    assertThat(domainUnits).isNotEmpty();

    for (SourceUnit unit : domainUnits) {
      for (String imp : unit.imports()) {
        assertThat(imp)
            .as("Domain class %s must not depend on Flowable, Spring, JPA, or outer layers", unit.fqcn())
            .doesNotStartWith("org.flowable.")
            .doesNotStartWith("org.springframework.")
            .doesNotStartWith("jakarta.persistence.")
            .doesNotStartWith("tools.jackson.")
            .doesNotContain(".application.")
            .doesNotContain(".infrastructure.")
            .doesNotContain(".api.");
      }
    }
  }

  @Test
  void applicationLayerMustNotDependOnFlowableJpaWebOrInfrastructureAdapters() {
    List<SourceUnit> appUnits = units.stream().filter(u -> u.isInLayer("application")).toList();
    assertThat(appUnits).isNotEmpty();

    for (SourceUnit unit : appUnits) {
      for (String imp : unit.imports()) {
        assertThat(imp)
            .as(
                "Application class %s must not depend on Flowable, JPA, Web, or infrastructure/api layers",
                unit.fqcn())
            .doesNotStartWith("org.flowable.")
            .doesNotStartWith("jakarta.persistence.")
            .doesNotStartWith("org.springframework.data.jpa.")
            .doesNotStartWith("org.springframework.web.")
            .doesNotStartWith("org.springframework.http.")
            .doesNotContain(".infrastructure.")
            .doesNotContain(".api.");
      }
    }
  }

  @Test
  void flowableIsStrictlyIsolatedToFlowableInfrastructurePackage() throws IOException {
    for (SourceUnit unit : units) {
      boolean isFlowableAdapterPkg =
          unit.packageName().equals("poc.validationprocess.infrastructure.flowable");
      if (!isFlowableAdapterPkg) {
        for (String imp : unit.imports()) {
          assertThat(imp)
              .as("Non-Flowable-adapter class %s must not reference Flowable APIs", unit.fqcn())
              .doesNotStartWith("org.flowable.");
        }
        String source = Files.readString(unit.file());
        assertThat(source)
            .as("Non-Flowable-adapter source %s must not contain org.flowable references", unit.fqcn())
            .doesNotContain("org.flowable");
      }
    }
  }

  @Test
  void jpaIsStrictlyIsolatedToPersistenceInfrastructurePackages() {
    for (SourceUnit unit : units) {
      boolean isPersistencePkg = unit.packageName().endsWith(".infrastructure.persistence");
      if (!isPersistencePkg) {
        for (String imp : unit.imports()) {
          assertThat(imp)
              .as("Non-persistence class %s must not reference JPA or Spring Data JPA", unit.fqcn())
              .doesNotStartWith("jakarta.persistence.")
              .doesNotStartWith("org.springframework.data.jpa.");
        }
      }
    }
  }

  @Test
  void featuresFollowAcyclicDependencyDirectionAndDoNotAccessOtherFeaturesInfrastructure() {
    for (SourceUnit unit : units) {
      if (unit.isInFeature("validation")) {
        for (String imp : unit.imports()) {
          assertThat(imp)
              .as("validation feature (%s) must not depend on executionplan or validationprocess", unit.fqcn())
              .doesNotStartWith("poc.executionplan.")
              .doesNotStartWith("poc.validationprocess.");
        }
      } else if (unit.isInFeature("executionplan")) {
        for (String imp : unit.imports()) {
          assertThat(imp)
              .as("executionplan feature (%s) must not depend on validationprocess or validation infrastructure", unit.fqcn())
              .doesNotStartWith("poc.validationprocess.")
              .doesNotStartWith("poc.validation.infrastructure.");
        }
      } else if (unit.isInFeature("validationprocess")) {
        for (String imp : unit.imports()) {
          assertThat(imp)
              .as("validationprocess feature (%s) must not access another feature's infrastructure internals", unit.fqcn())
              .doesNotStartWith("poc.validation.infrastructure.")
              .doesNotStartWith("poc.executionplan.infrastructure.");
        }
      }
    }
  }
}
