package com.openplan.backend.global.config;

import com.openplan.backend.global.mail.LoggingMailDispatcher;
import com.openplan.backend.global.mail.MailDispatcher;
import com.openplan.backend.global.mail.MailProperties;
import com.openplan.backend.global.mail.SmtpMailDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 메일 발송 구성 (ST-B1-04/05 선행 인프라).
 *
 * <p><b>자격증명 유무로 구현을 고른다.</b> 있으면 실제 SMTP, 없으면 로그로만 남기는 대체 구현이다.
 * {@code @ConditionalOnProperty}로는 "값이 비어 있지 않음"을 표현할 수 없어 빈 메서드에서 직접 판단한다.
 *
 * <p>이 선택을 <b>기동 실패로 만들지 않는</b> 것이 요점이다. 시크릿을 강제하면 그 시크릿이 필요 없는
 * 팀원의 로컬까지 함께 멈춘다 — {@code JwtProperties}를 조건부 등록으로 돌린 것과 같은 판단이다(D-32).
 * 강제의 목적은 운영에서 메일이 조용히 안 나가는 것을 막는 것이므로, 그 경고는 기동 시 WARN 로그로 낸다.
 *
 * <p>🔴 <b>빈 이름을 {@code mailSender}로 두지 말 것.</b> Spring Boot의
 * {@code MailSenderPropertiesConfiguration}이 그 이름을 이미 쓰고 있어 컨텍스트가 기동하지 않는다
 * ({@code BeanDefinitionOverrideException} — 실제로 겪고 이름을 바꿨다).
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    /**
     * @param smtpUsername {@code spring.mail.username}. {@code .env}에 값이 없으면 빈 문자열로 들어온다
     *                     ({@code application.yaml}이 {@code ${MAIL_USERNAME:}}로 기본값을 비워 둔다)
     */
    @Bean
    public MailDispatcher mailDispatcher(ObjectProvider<JavaMailSender> javaMailSender,
                                         MailProperties mailProperties,
                                         @Value("${spring.mail.username:}") String smtpUsername) {
        JavaMailSender sender = javaMailSender.getIfAvailable();
        if (sender == null || smtpUsername.isBlank()) {
            return new LoggingMailDispatcher();
        }
        return new SmtpMailDispatcher(sender, resolveFrom(mailProperties, smtpUsername));
    }

    /**
     * 발신 주소가 비어 있으면 SMTP 계정을 그대로 쓴다 — 대부분의 제공자가 인증된 주소만 발신을 허용하므로
     * 이 폴백이 오히려 정상 동작에 가깝고, 설정 누락으로 발송이 거부되는 일을 줄인다.
     */
    private static String resolveFrom(MailProperties properties, String smtpUsername) {
        return properties.from().isBlank() ? smtpUsername : properties.from();
    }
}
