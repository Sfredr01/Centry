package com.centry.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ViewerTest {

    @Test
    void initialsFromFirstAndLastWord() {
        assertThat(Viewer.of("Sam Rivera").initials()).isEqualTo("SR");
        assertThat(Viewer.of("  ana maria  lopez ")).isEqualTo(new Viewer("ana maria  lopez", "AL"));
        assertThat(Viewer.of("Mom").initials()).isEqualTo("M");
        assertThat(Viewer.of("émile").initials()).isEqualTo("É");
    }
}
