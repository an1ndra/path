package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Singleton row: how alert email is delivered. */
@Entity
@Table(name = "mail_settings")
@Getter
@Setter
@NoArgsConstructor
public class MailSettings {

    /** Always 1: there is one gateway, so there is one mail configuration. */
    public static final Long SINGLETON = 1L;

    @Id
    private Long id = SINGLETON;

    private boolean enabled;

    private String host;

    private Integer port;

    private String username;

    /** AES-GCM ciphertext; never returned by the API. */
    @Column(length = 1024)
    private String passwordCipher;

    private String fromAddress;

    /** Comma separated recipients. */
    @Column(length = 1024)
    private String toAddress;

    private Instant updatedAt;
}
