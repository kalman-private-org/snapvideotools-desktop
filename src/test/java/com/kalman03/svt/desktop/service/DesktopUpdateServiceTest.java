package com.kalman03.svt.desktop.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.kalman03.svt.desktop.service.DesktopUpdateService.LatestRelease;

class DesktopUpdateServiceTest {

    @Test
    void shouldNotNotifyWhenInstalledAndLatestVersionsAreEqual() {
        DesktopUpdateService service = serviceWithVersion("1.2.3");
        LatestRelease release = release("1.2.3", true);

        assertThat(service.shouldNotify(release)).isFalse();
    }

    @Test
    void shouldIgnorePrefixWhitespaceAndBuildMetadataForEqualVersions() {
        assertThat(DesktopUpdateService.isNewerVersion(" v1.2.3+release.9 ", "1.2.3+local.4"))
                .isFalse();
    }

    @Test
    void shouldNotifyOnlyWhenLatestVersionIsStrictlyNewer() {
        DesktopUpdateService service = serviceWithVersion("1.9.9");

        assertThat(service.shouldNotify(release("v1.10.0", false))).isTrue();
        assertThat(service.shouldNotify(release("1.9.8", true))).isFalse();
    }

    @Test
    void shouldApplySemanticPreReleaseOrdering() {
        assertThat(DesktopUpdateService.isNewerVersion("2.0.0", "2.0.0-rc.1")).isTrue();
        assertThat(DesktopUpdateService.isNewerVersion("2.0.0-rc.1", "2.0.0")).isFalse();
        assertThat(DesktopUpdateService.isNewerVersion("2.0.0-rc.2", "2.0.0-rc.1")).isTrue();
    }

    @Test
    void shouldNotNotifyWhenEitherVersionIsInvalid() {
        DesktopUpdateService service = serviceWithVersion("@project.version@");

        assertThat(service.shouldNotify(release("1.2.3", true))).isFalse();
        assertThat(DesktopUpdateService.isNewerVersion("latest", "1.2.3")).isFalse();
    }

    private static DesktopUpdateService serviceWithVersion(String currentVersion) {
        DesktopUpdateService service = new DesktopUpdateService();
        ReflectionTestUtils.setField(service, "currentVersion", currentVersion);
        return service;
    }

    private static LatestRelease release(String latestVersion, boolean updateAvailable) {
        LatestRelease release = new LatestRelease();
        release.setAvailable(true);
        release.setUpdateAvailable(updateAvailable);
        release.setLatestVersion(latestVersion);
        return release;
    }
}
