package dev.spanktube.upstream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewPipeChaptersTest {

    @Test
    void extractsChaptersFromHtmlDescription() {
        String description = "Intro<br><a href=\"https://www.youtube.com/watch?v=video\">00:00</a> START<br>"
                + "<a href=\"https://www.youtube.com/watch?v=video&amp;t=62\">01:02</a> NEXT<br>"
                + "<a href=\"https://www.youtube.com/watch?v=video&amp;t=3723\">1:02:03</a> HOUR<br>";

        assertThat(NewPipeUpstreamProvider.chaptersFromDescription(description)).containsExactly(
                new VideoChapter("START", 0L),
                new VideoChapter("NEXT", 62L),
                new VideoChapter("HOUR", 3723L));
    }
}
