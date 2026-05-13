package pro.xpst.telegram;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Instant;

@Component
public class MessageFreshnessGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageFreshnessGuard.class);

    @Value("${pro.xpst.telegram.bot.skip-stale-on-startup:true}")
    private boolean enabled;

    @Value("${pro.xpst.telegram.bot.startup-grace-seconds:0}")
    private long graceSeconds;

    @Getter
    private long cutoffEpochSeconds;

    @PostConstruct
    void init() {
        this.cutoffEpochSeconds = Instant.now().getEpochSecond() - Math.max(0, this.graceSeconds);
        LOGGER.debug("MessageFreshnessGuard initialized: enabled={}, cutoffEpoch={}, grace={}s",
                this.enabled, this.cutoffEpochSeconds, this.graceSeconds);
    }

    public boolean isFresh(Message aMessage) {
        if (!this.enabled) return true;
        if (aMessage == null || aMessage.getDate() == null) return true;
        return aMessage.getDate().longValue() >= this.cutoffEpochSeconds;
    }
}
