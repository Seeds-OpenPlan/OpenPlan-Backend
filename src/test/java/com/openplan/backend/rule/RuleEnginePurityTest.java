package com.openplan.backend.rule;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Random;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * C-1(결정성) 구조 강제 — rule-engine-contract §1.
 *
 * 지금까지 C-1은 주석으로만 지켜졌다. 규칙이 V1~V6으로 늘어나기 전에 컴파일·테스트로 막는다.
 * 여기서 실패하면 "테스트를 고치는" 게 아니라 <b>엔진에서 그 의존을 빼는</b> 것이 정답이다.
 * (필요한 값은 전부 스냅샷 입력으로 주입 — 시각은 {@code PlanSnapshot.referenceTime()})
 */
@AnalyzeClasses(packages = "com.openplan.backend.rule", importOptions = ImportOption.DoNotIncludeTests.class)
class RuleEnginePurityTest {

    @ArchTest
    static final ArchRule 프레임워크_IO_네트워크_비의존 = noClasses().should()
            .dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "jakarta.validation..",
                    "org.hibernate..",
                    "com.openplan.backend..repository..",
                    "java.net..",
                    "java.nio.file..",
                    "java.sql..",
                    "javax.sql..")
            .because("C-1: rule/ 은 스냅샷 입력만 받는 순수 함수다. "
                    + "프레임워크·DB·파일·네트워크에 닿는 순간 골든(동일입력→동일판정)이 성립하지 않는다");

    @ArchTest
    static final ArchRule 비결정성_타입_금지 = noClasses().should()
            .dependOnClassesThat().belongToAnyOf(Clock.class, Random.class)
            .because("C-1: 시각은 referenceTime 주입, 난수 금지");

    @ArchTest
    static final ArchRule 현재시각_난수_호출_금지 = noClasses().should()
            .callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalTime.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .orShould().callMethod(Math.class, "random")
            .orShould().callMethod(UUID.class, "randomUUID")
            .because("C-1: now()/난수는 같은 입력에 다른 판정을 낸다. 시각은 PlanSnapshot.referenceTime()");
}
