package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProjectValidator 순수 단위 (DB 불요). 검증 규칙의 경계값을 결정적으로 고정한다 —
 * today를 인자로 받으므로 실시간 시계 의존이 없다(C4).
 */
class ProjectValidatorTest {

    private final ProjectValidator validator = new ProjectValidator();
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);

    @Nested
    @DisplayName("validateName (AC-02-3)")
    class ValidateName {

        @Test
        @DisplayName("C2 — name이 null(키 부재)이면 NPE가 아니라 422로 실패한다")
        void nullNameThrows422NotNpe() {
            assertThatThrownBy(() -> validator.validateName(null))
                    .isInstanceOf(OpenPlanException.class)
                    .extracting(e -> ((OpenPlanException) e).errorCode())
                    .isEqualTo(ErrorCode.E_COM_009);
        }

        @ParameterizedTest
        @DisplayName("빈 문자열·공백만 → 422")
        @ValueSource(strings = {"", "   ", "\t\n"})
        void blankNameThrows(String blank) {
            assertThatThrownBy(() -> validator.validateName(blank))
                    .isInstanceOf(OpenPlanException.class);
        }

        @Test
        @DisplayName("trim 후 100자 초과 → 422 / 정확히 100자 → 통과(경계값)")
        void lengthBoundary() {
            String len100 = "가".repeat(100);
            String len101 = "가".repeat(101);
            assertThat(validator.validateName(len100)).isEqualTo(len100);
            assertThatThrownBy(() -> validator.validateName(len101))
                    .isInstanceOf(OpenPlanException.class);
        }

        @Test
        @DisplayName("앞뒤 공백은 trim되어 영속된다")
        void trimsWhitespace() {
            assertThat(validator.validateName("  논문 작성  ")).isEqualTo("논문 작성");
        }
    }

    @Nested
    @DisplayName("validateDueDate (AC-02-4 · Q-J)")
    class ValidateDueDate {

        @Test
        @DisplayName("null(무기한) → 통과")
        void nullDueDateOk() {
            assertThatCode(() -> validator.validateDueDate(null, TODAY)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("당일 → 통과 / 어제 → 422(경계값)")
        void todayOkYesterdayRejected() {
            assertThatCode(() -> validator.validateDueDate(TODAY, TODAY)).doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validateDueDate(TODAY.minusDays(1), TODAY))
                    .isInstanceOf(OpenPlanException.class)
                    .extracting(e -> ((OpenPlanException) e).errorCode())
                    .isEqualTo(ErrorCode.E_COM_009);
        }

        @Test
        @DisplayName("미래 → 통과")
        void futureOk() {
            assertThatCode(() -> validator.validateDueDate(TODAY.plusDays(30), TODAY)).doesNotThrowAnyException();
        }
    }
}
