package com.kalman03.svt.desktop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.kalman03.svt.desktop.enums.TabType;

class VideoParsingServiceTest {

    private static final String DOUYIN_MODAL_URL = "https://www.douyin.com/user/"
            + "MS4wLjABAAAAz-Nssy-G6nNshJODTK3VpEpjWsH1pMHODDPexGS5K-D6EAo5iASK_qCGRb7M5Rbe"
            + "?from_tab_name=main&modal_id=7654075731068670345&vid=7677107975563246491";

    @Test
    void shouldRouteDouyinModalProfileUrlAsSingleVideo() {
        VideoParsingService service = new VideoParsingService(mock(AuthService.class));

        assertThat(service.resolveTabType(TabType.USER_PROFILE, DOUYIN_MODAL_URL)).isEqualTo(TabType.VIDEO_LINK);
        assertThat(service.normalizeVideoUrl(DOUYIN_MODAL_URL))
                .isEqualTo("https://www.douyin.com/video/7654075731068670345");
    }

    @Test
    void shouldKeepOrdinaryProfileUrlInProfileMode() {
        VideoParsingService service = new VideoParsingService(mock(AuthService.class));

        assertThat(service.resolveTabType(TabType.USER_PROFILE, "https://www.douyin.com/user/example"))
                .isEqualTo(TabType.USER_PROFILE);
    }

    @Test
    void shouldRejectSingleVideoRequestWithoutLoginToken() {
        AuthService authService = mock(AuthService.class);
        when(authService.getAccessToken()).thenReturn(null);
        VideoParsingService service = new VideoParsingService(authService);

        assertThatThrownBy(() -> service.parseVideoUrl("https://www.douyin.com/video/7654075731068670345"))
                .isInstanceOf(VideoParsingService.ApiParseException.class)
                .hasMessage("Login required");
        verify(authService).handleUnauthorized();
    }

    @Test
    void shouldRejectProfileRequestWithoutLoginToken() {
        AuthService authService = mock(AuthService.class);
        when(authService.getAccessToken()).thenReturn(null);
        VideoParsingService service = new VideoParsingService(authService);

        assertThatThrownBy(() -> service.getUserVideos("https://www.douyin.com/user/example", 1, 20))
                .isInstanceOf(VideoParsingService.ApiParseException.class)
                .hasMessage("Login required");
        verify(authService).handleUnauthorized();
    }
}
