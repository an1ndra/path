package com.anindra.path.repo;

import com.anindra.path.domain.MailSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailSettingsRepository extends JpaRepository<MailSettings, Long> {
}
