package com.revolutclone.messaging.testsupport;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal bootable app for {@code @SpringBootTest} in this library module. Deliberately placed
 * outside {@code com.revolutclone.messaging} so its default component scan doesn't re-discover
 * (and double-register) the beans {@link com.revolutclone.messaging.MessagingAutoConfiguration}
 * already wires in via explicit {@code @ComponentScan}.
 */
@SpringBootApplication
public class TestMessagingApplication {
}
