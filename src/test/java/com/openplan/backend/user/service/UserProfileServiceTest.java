package com.openplan.backend.user.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.user.domain.LoginType;
import com.openplan.backend.user.domain.SocialProvider;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserProfile;
import com.openplan.backend.user.domain.Weekday;
import com.openplan.backend.user.dto.UpdateProfileRequest;
import com.openplan.backend.user.dto.UserProfileResponse;
import com.openplan.backend.user.repository.UserProfileRepository;
import com.openplan.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 프로필 서비스 단위 테스트(DB 불요 — 리포지토리 목킹). 조립·부분 수정·미존재·시간대 검증을 다룬다.
 * 엔티티는 세터가 없으므로 {@link ReflectionTestUtils}로 필드를 채워 고정 픽스처를 만든다.
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private UserProfileService service;

    @Test
    void getMyProfile_계정과_프로필을_조립한다() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "a@ex.com", LoginType.SOCIAL, SocialProvider.GOOGLE)));
        when(userProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile(USER_ID, "지훈", "취준", "Asia/Seoul", Weekday.MON)));

        UserProfileResponse res = service.getMyProfile(USER_ID);

        assertThat(res.userId()).isEqualTo(USER_ID);
        assertThat(res.email()).isEqualTo("a@ex.com");
        assertThat(res.loginType()).isEqualTo(LoginType.SOCIAL);
        assertThat(res.socialProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(res.name()).isEqualTo("지훈");
        assertThat(res.purpose()).isEqualTo("취준");
        assertThat(res.timezone()).isEqualTo("Asia/Seoul");
        assertThat(res.weekStartDay()).isEqualTo(Weekday.MON);
    }

    @Test
    void getMyProfile_계정이_없으면_E_COM_004() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProfile(USER_ID))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_004));
    }

    @Test
    void getMyProfile_프로필이_없으면_E_COM_004() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "a@ex.com", LoginType.LOCAL, null)));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProfile(USER_ID))
                .isInstanceOfSatisfying(OpenPlanException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_004));
    }

    @Test
    void updateProfile_제공된_필드만_수정한다() {
        UserProfile profile = profile(USER_ID, "old", "oldPurpose", "Asia/Seoul", Weekday.SUN);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "a@ex.com", LoginType.LOCAL, null)));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        // name·weekStartDay만 제공 — purpose·timezone은 미제공(null)이라 불변
        UserProfileResponse res = service.updateProfile(USER_ID,
                new UpdateProfileRequest("new", null, null, "WED"));

        assertThat(profile.getName()).isEqualTo("new");
        assertThat(profile.getWeekStartDay()).isEqualTo(Weekday.WED);
        assertThat(profile.getPurpose()).isEqualTo("oldPurpose");   // 불변
        assertThat(profile.getTimezone()).isEqualTo("Asia/Seoul");  // 불변
        assertThat(res.name()).isEqualTo("new");
        assertThat(res.weekStartDay()).isEqualTo(Weekday.WED);
    }

    @Test
    void updateProfile_모든_필드가_null이면_변경없음() {
        UserProfile profile = profile(USER_ID, "keep", "keepP", "Asia/Seoul", Weekday.MON);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "a@ex.com", LoginType.LOCAL, null)));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        service.updateProfile(USER_ID, new UpdateProfileRequest(null, null, null, null));

        assertThat(profile.getName()).isEqualTo("keep");
        assertThat(profile.getPurpose()).isEqualTo("keepP");
        assertThat(profile.getTimezone()).isEqualTo("Asia/Seoul");
        assertThat(profile.getWeekStartDay()).isEqualTo(Weekday.MON);
    }

    @Test
    void updateProfile_실재하지_않는_시간대는_E_COM_009() {
        UserProfile profile = profile(USER_ID, "n", null, "Asia/Seoul", Weekday.MON);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(USER_ID, "a@ex.com", LoginType.LOCAL, null)));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.updateProfile(USER_ID,
                new UpdateProfileRequest(null, null, "Asia/Nowhere", null)))
                .isInstanceOfSatisfying(OpenPlanException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.E_COM_009);
                    assertThat(ex.details()).containsEntry("field", "timezone");
                });
        assertThat(profile.getTimezone()).isEqualTo("Asia/Seoul"); // 저장 전 차단
    }

    // ---- 픽스처 헬퍼 (엔티티는 세터가 없어 리플렉션으로 조립) ----

    private static User user(UUID id, String email, LoginType loginType, SocialProvider provider) {
        User u = instantiate(User.class);
        ReflectionTestUtils.setField(u, "userId", id);
        ReflectionTestUtils.setField(u, "email", email);
        ReflectionTestUtils.setField(u, "loginType", loginType);
        ReflectionTestUtils.setField(u, "socialProvider", provider);
        return u;
    }

    private static UserProfile profile(UUID userId, String name, String purpose, String tz, Weekday weekday) {
        UserProfile p = instantiate(UserProfile.class);
        ReflectionTestUtils.setField(p, "profileId", UUID.randomUUID());
        ReflectionTestUtils.setField(p, "userId", userId);
        ReflectionTestUtils.setField(p, "name", name);
        ReflectionTestUtils.setField(p, "purpose", purpose);
        ReflectionTestUtils.setField(p, "timezone", tz);
        ReflectionTestUtils.setField(p, "weekStartDay", weekday);
        return p;
    }

    /** 엔티티의 protected 무인자 생성자(JPA 전용)를 테스트에서 호출하기 위한 리플렉션 인스턴스화. */
    private static <T> T instantiate(Class<T> type) {
        try {
            java.lang.reflect.Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 픽스처 생성 실패: " + type.getSimpleName(), e);
        }
    }
}
