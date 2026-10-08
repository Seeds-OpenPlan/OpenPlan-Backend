package com.openplan.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} — ADR-0015 결정 ①이 ADR-0006의 스케줄러 금지를 유일하게 푸는 지점이다.
 * 등록되는 {@code @Scheduled} 메서드는 {@code PushReminderDispatcher.tick()} 하나뿐이다(1분 주기 푸시
 * 리마인더 발송기). 다른 배치를 추가하려면 먼저 ADR를 다시 열어야 한다 — 이 애너테이션이 있다고
 * 아무 데서나 {@code @Scheduled}를 더해도 되는 것은 아니다.
 */
@EnableScheduling
@SpringBootApplication
public class OpenplanBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpenplanBackendApplication.class, args);
	}
}
