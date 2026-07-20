package com.openplan.backend.availability.service;

import com.openplan.backend.availability.domain.AvailabilityPattern;
import com.openplan.backend.availability.dto.AvailabilityPatternDto;
import com.openplan.backend.availability.dto.AvailabilityView;
import com.openplan.backend.availability.dto.SaveAvailabilitiesRequest;
import com.openplan.backend.availability.repository.AvailabilityPatternRepository;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 가용 시간 서비스 단위 테스트(DB 불요). 뷰 조립(정렬·총합)·전체 교체 저장·검증(5분/시작&lt;종료/7요일)을 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private AvailabilityPatternRepository repository;

    @InjectMocks
    private AvailabilityService service;

    @Test
    void getMyAvailabilities_요일순_정렬_및_활성만_총합() {
        // 입력은 요일 뒤섞임 + 일부 비활성 → 출력은 MON..SUN 정렬, 총합은 활성만
        List<AvailabilityPattern> stored = new ArrayList<>();
        stored.add(AvailabilityPattern.create(USER_ID, Weekday.WED, time(9, 0), time(12, 0), true));  // 180
        stored.add(AvailabilityPattern.create(USER_ID, Weekday.MON, time(9, 0), time(18, 0), true));  // 540
        stored.add(AvailabilityPattern.create(USER_ID, Weekday.SUN, time(0, 0), time(1, 0), false));  // 비활성 → 0
        when(repository.findByUserId(USER_ID)).thenReturn(stored);

        AvailabilityView view = service.getMyAvailabilities(USER_ID);

        assertThat(view.patterns()).extracting(AvailabilityPatternDto::weekday)
                .containsExactly(Weekday.MON, Weekday.WED, Weekday.SUN); // 요일 순
        assertThat(view.weeklyTotalMinutes()).isEqualTo(720); // 540 + 180 (SUN 비활성 제외)
    }

    @Test
    void saveAvailabilities_전체교체_후_뷰반환() {
        when(repository.saveAll(any())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));

        AvailabilityView view = service.saveAvailabilities(USER_ID,
                new SaveAvailabilitiesRequest(sevenActive(time(9, 0), time(18, 0))));

        assertThat(view.patterns()).hasSize(7);
        assertThat(view.weeklyTotalMinutes()).isEqualTo(7 * 540); // 요일 7 × 9시간
    }

    @Test
    void saveAvailabilities_5분_단위_위반은_E_COM_009() {
        List<AvailabilityPatternDto> patterns = sevenActive(time(9, 0), time(18, 0));
        patterns.set(0, new AvailabilityPatternDto(Weekday.MON, LocalTime.of(9, 3), time(18, 0), true)); // 09:03

        assertThatThrownBy(() -> service.saveAvailabilities(USER_ID, new SaveAvailabilitiesRequest(patterns)))
                .isInstanceOfSatisfying(OpenPlanException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_009);
                    assertThat(ex.details()).containsEntry("rule", "step");
                });
    }

    @Test
    void saveAvailabilities_시작이_종료보다_늦으면_E_COM_009() {
        List<AvailabilityPatternDto> patterns = sevenActive(time(9, 0), time(18, 0));
        patterns.set(2, new AvailabilityPatternDto(Weekday.WED, time(18, 0), time(9, 0), true)); // start>end

        assertThatThrownBy(() -> service.saveAvailabilities(USER_ID, new SaveAvailabilitiesRequest(patterns)))
                .isInstanceOfSatisfying(OpenPlanException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_009);
                    assertThat(ex.details()).containsEntry("rule", "range");
                });
    }

    @Test
    void saveAvailabilities_요일_중복_누락은_E_COM_009() {
        List<AvailabilityPatternDto> patterns = sevenActive(time(9, 0), time(18, 0));
        patterns.set(6, new AvailabilityPatternDto(Weekday.MON, time(9, 0), time(18, 0), true)); // SUN→MON 중복(SUN 누락)

        assertThatThrownBy(() -> service.saveAvailabilities(USER_ID, new SaveAvailabilitiesRequest(patterns)))
                .isInstanceOfSatisfying(OpenPlanException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_009);
                    assertThat(ex.details()).containsEntry("rule", "weekday_set");
                });
    }

    private static LocalTime time(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    /** 요일 7행(MON..SUN) 모두 활성, 동일 시간대. */
    private static List<AvailabilityPatternDto> sevenActive(LocalTime start, LocalTime end) {
        List<AvailabilityPatternDto> list = new ArrayList<>();
        for (Weekday w : Weekday.values()) {
            list.add(new AvailabilityPatternDto(w, start, end, true));
        }
        return list;
    }
}
