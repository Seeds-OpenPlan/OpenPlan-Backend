package com.openplan.backend.project.entity;

import com.openplan.backend.project.domain.ProjectStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 전이표 검증 (SSM T1~T6) — DB 불요 순수 단위. 3×3 전 조합을 고정한다.
 * 불허는 CLOSED→PAUSED(T6) 단 1건.
 */
class ProjectStatusTest {

    @ParameterizedTest(name = "{0} → {1} 허용={2}")
    @DisplayName("수동 전이 허용 여부 — 불허는 CLOSED→PAUSED 하나뿐")
    @CsvSource({
            "IN_PROGRESS, IN_PROGRESS, true",
            "IN_PROGRESS, PAUSED,      true",   // T1
            "IN_PROGRESS, CLOSED,      true",   // T2
            "PAUSED,      IN_PROGRESS, true",   // T3
            "PAUSED,      PAUSED,      true",
            "PAUSED,      CLOSED,      true",   // T4
            "CLOSED,      IN_PROGRESS, true",   // T5 (재개 허용, Q-C1)
            "CLOSED,      PAUSED,      false",  // T6 (유일 불허)
            "CLOSED,      CLOSED,      true"
    })
    void canTransitionTo(ProjectStatus from, ProjectStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }
}
