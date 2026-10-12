package com.hookshot.delivery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hookshot.config.HookshotProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TargetValidatorTest {

    private static TargetValidator validator(boolean allowPrivate) {
        HookshotProperties props = new HookshotProperties(
                "",
                8,
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                allowPrivate,
                new HookshotProperties.Worker(true, 20, Duration.ofSeconds(60), Duration.ofSeconds(5)));
        return new TargetValidator(props);
    }

    @Test
    void rejectsNonHttpSchemes() {
        TargetValidator v = validator(false);
        assertThatThrownBy(() -> v.validate("ftp://example.com/file")).isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> v.validate("file:///etc/passwd")).isInstanceOf(InvalidTargetException.class);
    }

    @Test
    void rejectsMalformedUrlsAndMissingHosts() {
        TargetValidator v = validator(false);
        assertThatThrownBy(() -> v.validate("not a url")).isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> v.validate("http:///path")).isInstanceOf(InvalidTargetException.class);
    }

    @Test
    void rejectsCredentialsInUrl() {
        assertThatThrownBy(() -> validator(false).validate("https://user:pass@8.8.8.8/hook"))
                .isInstanceOf(InvalidTargetException.class);
    }

    @Test
    void rejectsPrivateAndMetadataAddresses() {
        TargetValidator v = validator(false);
        assertThatThrownBy(() -> v.validate("http://127.0.0.1:8080/hook")).isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> v.validate("http://10.0.0.5/hook")).isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> v.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> v.validate("http://[::1]/hook")).isInstanceOf(InvalidTargetException.class);
    }

    @Test
    void acceptsPublicAddressLiterals() {
        assertThatCode(() -> validator(false).validate("https://8.8.8.8/hook")).doesNotThrowAnyException();
    }

    @Test
    void allowPrivateFlagDisablesAddressCheckButNotSchemeCheck() {
        TargetValidator v = validator(true);
        assertThatCode(() -> v.validate("http://127.0.0.1:9000/hook")).doesNotThrowAnyException();
        assertThatThrownBy(() -> v.validate("ftp://127.0.0.1/hook")).isInstanceOf(InvalidTargetException.class);
    }
}
