package com.example.videodownloader.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeSubtitlesTest {
    @Test
    fun convertsSrv1ToSrt() {
        val xml = """<?xml version="1.0" encoding="utf-8" ?><transcript>""" +
            """<text start="1.36" dur="1.68">[♪♪♪]</text>""" +
            """<text start="18.64" dur="5.0">We&amp;#39;re no strangers</text>""" +
            """<text start="22.64" dur="4.32">You know the rules
and so do I</text>""" +
            """<text start="30" dur="1"> </text></transcript>"""
        val cues = YouTubeSubtitles.parseSrv1(xml)
        assertEquals(3, cues.size)
        assertEquals("We're no strangers", cues[1].text)
        assertEquals(22.64, cues[1].end, 0.001) // 다음 자막과 겹치는 부분은 잘린다
        assertEquals(
            "1\n00:00:01,360 --> 00:00:03,040\n[♪♪♪]\n\n" +
                "2\n00:00:18,640 --> 00:00:22,640\nWe're no strangers\n\n" +
                "3\n00:00:22,640 --> 00:00:26,960\nYou know the rules\nand so do I\n\n",
            YouTubeSubtitles.toSrt(cues),
        )
    }
}
