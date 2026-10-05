package com.openplan.backend.externalcalendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 외부 캘린더 연동 (ONB-07 · FIX-13/14/16/17) — baseline {@code external_calendar_connections} 매핑.
 *
 * <p><b>토큰은 암호문으로만 이 안에 있다.</b> 평문은 {@code ExternalTokenCipher} 를 지나는 순간에만
 * 존재하고 엔티티·로그·응답 어디에도 남지 않는다. 그래서 게터 이름도 {@code ...Enc} 로 두어
 * 무심코 제공자에 그대로 실어 보내지 못하게 했다.
 *
 * <p><b>중복 연결은 DB 가 막는다</b> — UQ(user_id, provider, account_identifier)가 409 E-EXT-004 의
 * 구조적 근거다(ST-B1-11 AC1). 서버도 저장 전에 확인하지만, 동시 요청은 제약이 최종 판정한다.
 *
 * <p>연동을 해제하면 이 행이 사라지고, {@code fixed_schedules.connection_id} 의 ON DELETE CASCADE 로
 * EXTERNAL 유래 고정 일정이 함께 사라진다(FIX-17 · AC4).
 */
@Getter
@Entity
@Table(name = "external_calendar_connections")
public class ExternalCalendarConnection {

    @Id
    @Column(name = "connection_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ExternalCalendarProvider provider;

    /** 제공자 계정 식별자(구글은 이메일). 중복 연결 판정의 구성 요소라 변경하지 않는다. */
    @Column(name = "account_identifier", nullable = false, length = 255, updatable = false)
    private String accountIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_mode", nullable = false, length = 20)
    private SyncMode syncMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** AES-GCM 암호문. 평문 접근은 서비스가 cipher 를 통해서만 한다. */
    @Column(name = "access_token_enc", nullable = false)
    private String accessTokenEnc;

    /** 제공자가 주지 않으면 null — 그 경우 만료 후 재연동이 필요하다. */
    @Column(name = "refresh_token_enc")
    private String refreshTokenEnc;

    /** access 토큰 만료 시각. 제공자가 알려주지 않으면 null. */
    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    /**
     * 제공자가 <b>실제로 부여한</b> 스코프(공백 구분). null 이면 모른다.
     *
     * <p>🔴 우리가 요청한 값이 아니다. 사용자가 동의 화면에서 일부만 허용할 수 있고, <b>스코프를
     * 넓히기 전에 연동한 사용자는 옛 권한의 토큰을 들고 있다.</b> 그 토큰으로 쓰기를 시도하면
     * 제공자가 403 을 주고, 화면에는 "연동돼 있는데 안 된다" 만 남는다. 요청값으로 판단하면
     * 그 차이를 영영 볼 수 없다.
     */
    @Column(name = "granted_scope", columnDefinition = "text")
    private String grantedScope;

    /**
     * 새 일정을 만들 대상 캘린더 (#69). null 이면 <b>아직 안 골랐다</b>.
     *
     * <p>🔴 읽기만 할 때는 없던 문제다. 연동이 여럿이고 캘린더도 여럿이면 «어디에 만들 것인가» 를
     * 정해야 하고, 아무 데나 고르면 <b>사용자가 모르는 곳에 일정이 생긴다.</b> 안 골랐으면
     * 내보내지 않는다 — 모르면 쓰지 않는다.
     */
    @Column(name = "write_calendar_id", length = 512)
    private String writeCalendarId;

    /** JPA 전용. */
    protected ExternalCalendarConnection() {
    }

    /** 연동 생성 (ONB-07 · FIX-14) — 인가 코드 교환 성공이 전제라 access 암호문이 반드시 있다. */
    public static ExternalCalendarConnection connect(UUID userId, ExternalCalendarProvider provider,
                                                     String accountIdentifier, String accessTokenEnc,
                                                     String refreshTokenEnc, Instant tokenExpiresAt,
                                                     String grantedScope, Instant now) {
        ExternalCalendarConnection connection = new ExternalCalendarConnection();
        connection.id = UUID.randomUUID();
        connection.userId = userId;
        connection.provider = provider;
        connection.accountIdentifier = accountIdentifier;
        connection.syncMode = SyncMode.MANUAL;
        connection.status = ConnectionStatus.ACTIVE;
        connection.connectedAt = now;
        connection.createdAt = now;
        connection.accessTokenEnc = accessTokenEnc;
        connection.refreshTokenEnc = refreshTokenEnc;
        connection.tokenExpiresAt = tokenExpiresAt;
        connection.grantedScope = grantedScope;
        return connection;
    }

    /** FIX-16 활성/비활성. 유래 고정 일정 미러는 서비스가 같은 트랜잭션에서 함께 처리한다(AC3). */
    public void changeStatus(ConnectionStatus newStatus) {
        this.status = newStatus;
    }

    /** 토큰 갱신 결과 반영. refresh 토큰을 새로 주지 않는 제공자가 있어 기존 값을 지우지 않는다. */
    /**
     * 부여 스코프를 최신으로 맞춘다. <b>값이 왔을 때만</b> 갱신한다 — 제공자가 갱신 응답에서
     * scope 를 생략하는 경우가 있고, 그때 지우면 쓸 수 있던 연동이 «모름» 으로 돌아간다.
     */
    public void updateGrantedScope(String grantedScope) {
        if (grantedScope != null && !grantedScope.isBlank()) {
            this.grantedScope = grantedScope;
        }
    }

    /**
     * 이 연동으로 <b>밖에 쓸 수 있는가</b> (#69).
     *
     * <p>애플(CalDAV)은 스코프 개념이 없다 — 앱 암호가 곧 전권이라 붙을 수 있으면 쓸 수 있다.
     *
     * <p>OAuth 제공자는 <b>부여받은 스코프에 쓰기 범위가 실제로 들어 있을 때만</b> 참이다.
     * null(모름)은 거짓 — 스코프를 넓히기 전에 연동한 행이 여기 해당하고, 그 토큰으로 쓰면
     * 403 이 난다. <b>모르면 쓰지 않는다.</b>
     */
    public boolean canWrite() {
        if (provider.authModel() == ExternalCalendarProvider.CalendarAuthModel.CALDAV_BASIC) {
            return isActive();
        }
        return isActive() && grantedScope != null && grantedScope.contains(WRITE_SCOPE_MARKER);
    }

    /** 구글 쓰기 범위의 표식. {@code calendar} 단독(전권)도 쓰기를 포함하지만 우리는 요청하지 않는다. */
    private static final String WRITE_SCOPE_MARKER = "auth/calendar.events";

    /** 사용자가 설정 화면에서 대상 캘린더를 고른다. null 로 되돌리면 내보내기가 멈춘다. */
    public void chooseWriteCalendar(String writeCalendarId) {
        this.writeCalendarId = writeCalendarId;
    }

    public String getWriteCalendarId() {
        return writeCalendarId;
    }

    public String getGrantedScope() {
        return grantedScope;
    }

    public void refreshTokens(String accessTokenEnc, String refreshTokenEnc, Instant tokenExpiresAt) {
        this.accessTokenEnc = accessTokenEnc;
        if (refreshTokenEnc != null) {
            this.refreshTokenEnc = refreshTokenEnc;
        }
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public boolean isActive() {
        return status == ConnectionStatus.ACTIVE;
    }
}
