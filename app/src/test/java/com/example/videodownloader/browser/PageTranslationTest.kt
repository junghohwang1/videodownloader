package com.example.videodownloader.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationTest {

    @Test
    fun restoresOriginalUrlFromProxy() {
        assertEquals(
            "https://www.example.com/news/a?id=3",
            PageTranslation.originalUrl("https://www-example-com.translate.goog/news/a?id=3&_x_tr_sl=auto&_x_tr_tl=ko&_x_tr_hl=ko"),
        )
        assertEquals(
            "https://my-site.co.uk/",
            PageTranslation.originalUrl("https://my--site-co-uk.translate.goog/?_x_tr_sl=auto"),
        )
        assertEquals(
            "https://example.org/page",
            PageTranslation.originalUrl(PageTranslation.translateUrl("https://example.org/page")),
        )
    }

    @Test
    fun detectsTranslatedPages() {
        assertTrue(PageTranslation.isTranslated("https://www-bbc-com.translate.goog/news"))
        assertFalse(PageTranslation.isTranslated("https://www.bbc.com/news"))
    }

    @Test
    fun decidesForeignPages() {
        assertTrue(PageTranslation.looksForeign("en", 0, 1500))
        assertFalse(PageTranslation.looksForeign("ko", 0, 1500))
        assertFalse(PageTranslation.looksForeign("en", 900, 600)) // lang 속성이 틀려도 본문이 한국어면 제외
        assertTrue(PageTranslation.looksForeign("", 3, 2000))
        assertFalse(PageTranslation.looksForeign("", 0, 50)) // 글자가 너무 적으면 판단하지 않음
    }
}
