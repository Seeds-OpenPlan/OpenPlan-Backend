package com.openplan.backend.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpdateProfileRequest} Bean Validation 규칙 테스트(DB·스프링 컨텍스트 불요).
 * 위반은 컨트롤러에서 E-COM-001로 매핑되므로, 여기서 위반 필드 집합만 확정한다.
 */
class UpdateProfileRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 모든_필드가_null이면_위반없음_부분수정_허용() {
        assertThat(validator.validate(new UpdateProfileRequest(null, null, null, null))).isEmpty();
    }

    @Test
    void 정상값은_위반없음() {
        var req = new UpdateProfileRequest("전창현", "취업 준비", "Asia/Seoul", "MON");
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void name_50자_초과는_위반() {
        var req = new UpdateProfileRequest("a".repeat(51), null, null, null);
        assertThat(violatedFields(req)).containsExactly("name");
    }

    @Test
    void name_빈문자열은_위반() {
        var req = new UpdateProfileRequest("", null, null, null);
        assertThat(violatedFields(req)).containsExactly("name");
    }

    @Test
    void purpose_100자_초과는_위반() {
        var req = new UpdateProfileRequest(null, "p".repeat(101), null, null);
        assertThat(violatedFields(req)).containsExactly("purpose");
    }

    @Test
    void weekStartDay_허용값_밖은_위반() {
        var req = new UpdateProfileRequest(null, null, null, "FUNDAY");
        assertThat(violatedFields(req)).containsExactly("weekStartDay");
    }

    private Set<String> violatedFields(UpdateProfileRequest req) {
        return validator.validate(req).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }
}
